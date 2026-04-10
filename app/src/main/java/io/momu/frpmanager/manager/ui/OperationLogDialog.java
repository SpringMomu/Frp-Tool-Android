package io.momu.frpmanager.manager.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.momu.frpmanager.R;

public class OperationLogDialog {

    private final Activity activity;
    private final AlertDialog dialog;
    private final TextView logTextView;
    private final ScrollView logScrollView;
    private final StringBuilder logBuilder = new StringBuilder();

    public OperationLogDialog(Activity activity, String title, boolean cancelable) {
        this.activity = activity;
        View logView = LayoutInflater.from(activity).inflate(R.layout.dialog_log_viewer, null);
        this.logTextView = logView.findViewById(R.id.log_text_view);
        this.logScrollView = logView.findViewById(R.id.log_scroll_view);
        Button copyButton = logView.findViewById(R.id.btn_copy_log);

        logTextView.setMovementMethod(new ScrollingMovementMethod());
        this.dialog = new MaterialAlertDialogBuilder(activity)
            .setView(logView)
            .setTitle(title)
            .setCancelable(cancelable)
            .setPositiveButton("关闭", null)
            .create();

        dialog.show();
        Button closeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (closeButton != null) {
            closeButton.setEnabled(false);
        }

        copyButton.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("FRP Manager Log", logBuilder.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(activity, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });
    }

    public void setTitle(String title) {
        activity.runOnUiThread(() -> dialog.setTitle(title));
    }

    public void enableCloseButton(boolean enabled) {
        activity.runOnUiThread(() -> {
            Button closeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (closeButton != null) {
                closeButton.setEnabled(enabled);
            }
        });
    }

    public void dismissDelayed(long delayMillis) {
        new Handler(Looper.getMainLooper()).postDelayed(this::dismissSafely, delayMillis);
    }

    public void dismissSafely() {
        activity.runOnUiThread(() -> {
            if (dialog.isShowing() && !activity.isFinishing()) {
                dialog.dismiss();
            }
        });
    }

    public void append(String message, String type) {
        activity.runOnUiThread(() -> {
            String formattedMessage = formatMessage(message, type);
            logBuilder.append(message).append('\n');
            logTextView.append(Html.fromHtml(formattedMessage, Html.FROM_HTML_MODE_LEGACY));
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private String formatMessage(String message, String type) {
        String encoded = TextUtils.htmlEncode(message).replace("\n", "<br>");
        switch (type) {
            case "success":
                return "<font color='#4CAF50'>" + encoded + "</font><br>";
            case "error":
                return "<font color='#F44336'>" + encoded + "</font><br>";
            case "warning":
                return "<font color='#FF9800'>" + encoded + "</font><br>";
            case "info":
            default:
                return encoded + "<br>";
        }
    }
}
