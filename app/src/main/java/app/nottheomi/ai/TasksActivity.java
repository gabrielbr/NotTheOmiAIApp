package app.nottheomi.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
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
        LinearLayout root = Ui.page(this);
        setContentView(root);
        root.addView(Ui.backBar(this, null));
        root.addView(Ui.divider(this));
        ScrollView scroll = new ScrollView(this);
        page = Ui.column(this);
        page.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(Ui.title(this, "Tasks for Todoist", "Tasks"));
        status = Ui.text(this, "Looking for tasks…", 15, Ui.MUTED, false);
        status.setPadding(0, dp(10), 0, dp(12));
        page.addView(status);
        rows = Ui.column(this); page.addView(rows);
        Button addTask = Ui.button(this, "Add a task", Ui.Style.QUIET, v -> row("", true));
        Ui.icon(this, addTask, R.drawable.ic_plus, Ui.INK);
        addTask.setPadding(0, 0, dp(8), 0);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, dp(48)); ap.topMargin = dp(8);
        page.addView(addTask, ap);
        root.addView(Ui.divider(this));
        LinearLayout bar = Ui.column(this);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        send = Ui.button(this, "Send to Todoist", Ui.Style.PRIMARY, v -> sendSelected());
        bar.addView(send, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView note = Ui.text(this, "Todoist opens once per task so you can confirm it.", 13, Ui.MUTED, false);
        note.setGravity(android.view.Gravity.CENTER);
        note.setPadding(0, dp(10), 0, 0);
        bar.addView(note);
        root.addView(bar);

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
        LinearLayout line = Ui.row(this);
        line.setPadding(0, dp(6), 0, dp(6));
        CheckBox check = new CheckBox(this); check.setChecked(selected);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.INK));
        check.setContentDescription("Include task");
        check.setOnCheckedChangeListener((b, on) -> updateStatus());
        EditText field = new EditText(this); field.setText(value); field.setTextColor(Ui.INK); field.setTextSize(17);
        field.setTypeface(Ui.font(this, false));
        field.setBackground(null);
        field.setPadding(dp(6), dp(8), 0, dp(8));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TaskExtractor.MAX_TASK_CHARS)});
        field.setHint("New task");
        field.setHintTextColor(Ui.MUTED);
        line.addView(check, new LinearLayout.LayoutParams(-2, -2));
        line.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        rows.addView(Ui.divider(this));
        rows.addView(line);
        checks.add(check); fields.add(field);
        updateStatus();
    }

    private void updateStatus() {
        int selected = selectedTasks().size();
        if (!queue.isEmpty()) status.setText("Sending… " + queue.size() + " left");
        else if (fields.isEmpty()) status.setText("No tasks found. Add one yourself.");
        else status.setText(fields.size() == 1 ? "1 task found. Edit it before sending." : fields.size() + " tasks found. Edit or untick before sending.");
        send.setEnabled(selected > 0 && queue.isEmpty());
        send.setText(selected > 0 ? "Send " + selected + " to Todoist" : "Send to Todoist");
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

    private int dp(float value) { return Ui.dp(this, value); }
}
