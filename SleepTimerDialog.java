package com.cappielloantonio.tempo.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;

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

    private static final int[] MINUTE_VALUES = {5, 10, 15, 20, 30, 45, 60};
    private static final String[] MINUTE_LABELS = {
            "5 minutes", "10 minutes", "15 minutes",
            "20 minutes", "30 minutes", "45 minutes", "60 minutes"
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
                    if (listener != null) {
                        listener.onTimerSet(MINUTE_VALUES[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.sleep_timer_dialog_close, (dialog, id) -> dialog.cancel());

        if (timerActive) {
            String remaining = SleepTimerManager.getInstance().getRemainingFormatted();
            builder.setMessage(getString(R.string.sleep_timer_dialog_active_message, remaining));
            builder.setNeutralButton(R.string.sleep_timer_dialog_cancel_timer, (dialog, id) -> {
                if (listener != null) {
                    listener.onTimerCancelled();
                }
            });
        }

        return builder.create();
    }
}
