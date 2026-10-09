package app.nottheomi.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Review tasks found in one saved transcript, then hand each selected task to the
 * Todoist app's share target (Quick Add → Inbox). No network or token in this app:
 * the text leaves only through the explicit Android share the user confirms in Todoist.
 */
public final class TasksActivity extends Activity {
    public static final String EXTRA_SESSION_ID = "session_id";
    static final String TODOIST_PACKAGE = "com.todoist";
    private static final int SHARE_REQUEST = 40;
    private static final int PAPER = 0xfff5f2eb, INK = 0xff191c18, MUTED = 0xff646a61;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<CheckBox> checks = new ArrayList<>();
    private final List<EditText> fields = new ArrayList<>();
    private final ArrayList<String> queue = new ArrayList<>();
    private LinearLayout page, rows;
    private TextView status;
    private Button send;
    private boolean destroyed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(24)); page.setBackgroundColor(PAPER);
        scroll.addView(page); setContentView(scroll);
        add(button("‹  Back to recording", false, v -> finish()));
        page.addView(text("Tasks for Todoist", 26, INK, true));
        status = text("Looking for tasks in the transcript…", 13, MUTED, false);
        page.addView(status);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); page.addView(rows);
        add(button("+  Add a task", false, v -> row("", true)));
        send = button("Send selected to Todoist", true, v -> sendSelected());
        add(send);
        page.addView(text("Each task opens Todoist's Quick Add, which files it in your Inbox and "
                + "reads dates like \"amanhã\" or \"tomorrow\" in your Todoist language. "
                + "Confirm or cancel each one there.", 12, MUTED, false));

        if (state != null) {
            ArrayList<String> texts = state.getStringArrayList("texts");
            boolean[] selected = state.getBooleanArray("selected");
            ArrayList<String> pending = state.getStringArrayList("queue");
            if (texts != null && selected != null && selected.length == texts.size()) {
                for (int i = 0; i < texts.size(); i++) row(texts.get(i), selected[i]);
                if (pending != null) queue.addAll(pending);
                updateStatus();
                return;
            }
        }
        String id = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (id == null) { finish(); return; }
        io.execute(() -> {
            List<String> found;
            try {
                Recordings.Session s = Recordings.get(this).find(id);
                String transcript = s == null ? "" : s.text.trim().isEmpty() ? s.liveText : s.text;
                found = TaskExtractor.extract(transcript);
            } catch (Exception e) {
                main.post(() -> { if (!destroyed) status.setText("This transcript could not be read. No data was changed."); });
                return;
            }
            main.post(() -> {
                if (destroyed) return;
                for (String task : found) row(task, true);
                updateStatus();
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> texts = new ArrayList<>();
        boolean[] selected = new boolean[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            texts.add(fields.get(i).getText().toString());
            selected[i] = checks.get(i).isChecked();
        }
        out.putStringArrayList("texts", texts);
        out.putBooleanArray("selected", selected);
        out.putStringArrayList("queue", queue);
    }

    @Override protected void onDestroy() { destroyed = true; io.shutdown(); super.onDestroy(); }

    private void row(String value, boolean selected) {
        LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox check = new CheckBox(this); check.setChecked(selected);
        check.setOnCheckedChangeListener((b, on) -> updateStatus());
        EditText field = new EditText(this); field.setText(value); field.setTextColor(INK); field.setTextSize(16);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TaskExtractor.MAX_TASK_CHARS)});
        field.setHint("Task");
        line.addView(check, new LinearLayout.LayoutParams(-2, -2));
        line.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        rows.addView(line);
        checks.add(check); fields.add(field);
        updateStatus();
    }

    private void updateStatus() {
        int selected = selectedTasks().size();
        if (!queue.isEmpty()) status.setText("Sending to Todoist · " + queue.size() + " left");
        else if (fields.isEmpty()) status.setText("No tasks found. Add one below, or check the transcript first.");
        else status.setText(fields.size() + " found · " + selected + " selected. Edit before sending.");
        send.setEnabled(selected > 0 && queue.isEmpty());
        send.setText(selected > 0 ? "Send " + selected + " to Todoist" : "Send selected to Todoist");
    }

    private List<String> selectedTasks() {
        List<String> tasks = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            String task = fields.get(i).getText().toString().trim();
            if (checks.get(i).isChecked() && !task.isEmpty()) tasks.add(task);
        }
        return tasks;
    }

    private void sendSelected() {
        queue.clear();
        queue.addAll(selectedTasks());
        sendNext();
    }

    /** One share per task, so Todoist's Quick Add parses each task and date on its own. */
    private void sendNext() {
        if (queue.isEmpty()) { updateStatus(); return; }
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, queue.get(0)).setPackage(TODOIST_PACKAGE);
        try {
            startActivityForResult(share, SHARE_REQUEST);
            updateStatus();
        } catch (ActivityNotFoundException | SecurityException e) {
            queue.clear();
            updateStatus();
            new AlertDialog.Builder(this).setTitle("Todoist app not found")
                    .setMessage("Install and sign in to the Todoist app on this phone, then try again. "
                            + "Your tasks stay here until you send them.")
                    .setPositiveButton("OK", null).show();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != SHARE_REQUEST || queue.isEmpty()) return;
        // Todoist does not report whether the user saved or cancelled; move on either way.
        String sent = queue.remove(0);
        for (int i = 0; i < fields.size(); i++)
            if (checks.get(i).isChecked() && fields.get(i).getText().toString().trim().equals(sent)) {
                checks.get(i).setChecked(false);
                break;
            }
        sendNext();
    }

    private int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, dp(4), 0, dp(4));
        return v;
    }

    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : INK);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? INK : 0xffe5e8df); bg.setCornerRadius(dp(14));
        b.setBackground(bg); b.setMinHeight(dp(52)); b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setOnClickListener(click);
        return b;
    }

    private void add(View view) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(10);
        page.addView(view, p);
    }
}
