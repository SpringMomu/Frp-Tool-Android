package io.momu.frpmanager.manager.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import io.momu.frpmanager.R;
import io.momu.frpmanager.SensitiveDataRedactor;

/** Presents remote operations as a readable, animated process timeline. */
public class OperationLogDialog {

    private final Activity activity;
    private final AlertDialog dialog;
    private final ScrollView scrollView;
    private final LinearLayout timelineContainer;
    private final LinearProgressIndicator progressIndicator;
    private final TextView statusLabel;
    private final TextView statusDetail;
    private final ImageView statusIcon;
    private final StringBuilder logBuilder = new StringBuilder();
    private boolean hasError;
    private boolean hasWarning;

    public OperationLogDialog(Activity activity, String title, boolean cancelable) {
        this.activity = activity;
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_log_viewer, null);
        TextView rawText = content.findViewById(R.id.log_text_view);
        rawText.setVisibility(View.GONE);
        this.scrollView = content.findViewById(R.id.log_scroll_view);
        this.timelineContainer = content.findViewById(R.id.log_timeline_container);
        this.timelineContainer.setVisibility(View.VISIBLE);
        this.progressIndicator = content.findViewById(R.id.log_progress_indicator);
        this.progressIndicator.setVisibility(View.VISIBLE);
        this.statusLabel = content.findViewById(R.id.log_status_label);
        this.statusDetail = content.findViewById(R.id.log_status_detail);
        this.statusIcon = content.findViewById(R.id.log_header_icon);
        this.statusLabel.setText("正在执行");
        this.statusDetail.setText("步骤会在完成后依次出现");
        Button copyButton = content.findViewById(R.id.btn_copy_log);

        this.dialog = new MaterialAlertDialogBuilder(activity)
            .setView(content)
            .setTitle(title)
            .setCancelable(cancelable)
            .setPositiveButton("关闭", null)
            .create();

        dialog.show();
        Button closeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (closeButton != null) closeButton.setEnabled(false);

        copyButton.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(
                "FRP Manager Process", SensitiveDataRedactor.redact(logBuilder.toString())));
            Toast.makeText(activity, "执行详情已复制", Toast.LENGTH_SHORT).show();
        });
    }

    public void setTitle(String title) {
        activity.runOnUiThread(() -> dialog.setTitle(title));
    }

    public void enableCloseButton(boolean enabled) {
        activity.runOnUiThread(() -> {
            Button closeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (closeButton != null) closeButton.setEnabled(enabled);
            if (!enabled) return;
            progressIndicator.setVisibility(View.GONE);
            int color;
            if (hasError) {
                statusLabel.setText("部分步骤失败");
                statusDetail.setText("失败项已保持关闭，可查看下方详情");
                statusIcon.setImageResource(R.drawable.outline_report_problem_24);
                color = ContextCompat.getColor(activity, R.color.status_stopped);
            } else if (hasWarning) {
                statusLabel.setText("执行完成，存在提醒");
                statusDetail.setText("请检查标记为提醒的步骤");
                statusIcon.setImageResource(R.drawable.outline_report_problem_24);
                color = ContextCompat.getColor(activity, R.color.status_pending);
            } else {
                statusLabel.setText("执行完成");
                statusDetail.setText("所有已确认步骤均已完成");
                statusIcon.setImageResource(R.drawable.outline_check_circle_24);
                color = ContextCompat.getColor(activity, R.color.status_running);
            }
            statusLabel.setTextColor(color);
            ImageViewCompat.setImageTintList(statusIcon, ColorStateList.valueOf(color));
        });
    }

    public void dismissDelayed(long delayMillis) {
        new Handler(Looper.getMainLooper()).postDelayed(this::dismissSafely, delayMillis);
    }

    public void dismissSafely() {
        activity.runOnUiThread(() -> {
            if (dialog.isShowing() && !activity.isFinishing()) dialog.dismiss();
        });
    }

    public void append(String message, String type) {
        activity.runOnUiThread(() -> {
            String safeMessage = SensitiveDataRedactor.redact(message == null ? "" : message).trim();
            logBuilder.append(safeMessage).append('\n');
            if (safeMessage.isEmpty() || safeMessage.matches("^-{6,}$")) return;

            int color = colorFor(type);
            if ("error".equals(type)) hasError = true;
            if ("warning".equals(type)) hasWarning = true;
            View step = LayoutInflater.from(activity).inflate(R.layout.item_operation_step, timelineContainer, false);
            View dot = step.findViewById(R.id.step_dot);
            TextView label = step.findViewById(R.id.step_label);
            TextView body = step.findViewById(R.id.step_message);
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(color));
            label.setText(labelFor(type));
            label.setTextColor(color);
            body.setText(safeMessage);
            timelineContainer.addView(step);

            step.setAlpha(0f);
            step.setTranslationY(10f * activity.getResources().getDisplayMetrics().density);
            step.animate().alpha(1f).translationY(0f).setDuration(200L)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            scrollView.post(() -> scrollView.smoothScrollTo(0, timelineContainer.getBottom()));
        });
    }

    private int colorFor(String type) {
        if ("success".equals(type)) return ContextCompat.getColor(activity, R.color.status_running);
        if ("error".equals(type)) return ContextCompat.getColor(activity, R.color.status_stopped);
        if ("warning".equals(type)) return ContextCompat.getColor(activity, R.color.status_pending);
        return ContextCompat.getColor(activity, R.color.brand_primary);
    }

    private String labelFor(String type) {
        if ("success".equals(type)) return "完成";
        if ("error".equals(type)) return "失败";
        if ("warning".equals(type)) return "提醒";
        return "步骤";
    }
}
