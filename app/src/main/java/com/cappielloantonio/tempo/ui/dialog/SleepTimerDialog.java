package com.cappielloantonio.tempo.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.cappielloantonio.tempo.R;
import com.cappielloantonio.tempo.util.SleepTimerManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SleepTimerDialog extends DialogFragment {

    private static final String TAG = "SleepTimerDialog";

    public interface SleepTimerListener {
        void onTimerSet(int minutes);
        void onTimerCancelled();
    }

    private static final int CUSTOM = -1;
    private static final int[] MINUTE_VALUES  = {5, 10, 15, 20, 30, 45, 60, CUSTOM};
    private static final String[] MINUTE_LABELS = {
            "5 minutes", "10 minutes", "15 minutes",
            "20 minutes", "30 minutes", "45 minutes", "60 minutes",
            "Custom\u2026"
    };

    private SleepTimerListener listener;

    public void setSleepTimerListener(SleepTimerListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        boolean timerActive = SleepTimerManager.getInstance().isActive();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.sleep_timer_dialog_title)
                .setSingleChoiceItems(MINUTE_LABELS, -1, (dialog, which) -> {
                    if (MINUTE_VALUES[which] == CUSTOM) {
                        dialog.dismiss();
                        showCustomInputDialog();
                    } else {
                        if (listener != null) listener.onTimerSet(MINUTE_VALUES[which]);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(R.string.sleep_timer_dialog_close,
                        (dialog, id) -> dialog.cancel());

        if (timerActive) {
            String remaining = SleepTimerManager.getInstance().getRemainingFormatted();
            builder.setMessage(getString(R.string.sleep_timer_dialog_active_message, remaining));
            builder.setNeutralButton(R.string.sleep_timer_dialog_cancel_timer,
                    (dialog, id) -> {
                        if (listener != null) listener.onTimerCancelled();
                    });
        }

        return builder.create();
    }

    private void showCustomInputDialog() {
        android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.sleep_timer_custom_hint));
        int dp16 = Math.round(16 * getResources().getDisplayMetrics().density);
        input.setPadding(dp16, dp16, dp16, dp16);

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.sleep_timer_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.sleep_timer_custom_set, (d, which) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        try {
                            int minutes = Integer.parseInt(text);
                            if (minutes > 0 && listener != null) {
                                listener.onTimerSet(minutes);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                })
                .setNegativeButton(R.string.sleep_timer_dialog_close, null)
                .show();
    }
}
