package io.momu.frpmanager;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;

public class WelcomeFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_wizard_welcome, container, false);
        MaterialButton startButton = view.findViewById(R.id.btn_start_setup);
        startButton.setOnClickListener(v -> {
            if (getActivity() instanceof SetupWizardActivity) {
                ((SetupWizardActivity) getActivity()).navigateToNextStep();
            }
        });
        return view;
    }
}
