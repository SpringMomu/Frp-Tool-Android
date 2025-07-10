package io.momu.frpmanager;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import android.annotation.SuppressLint;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class SetupWizardActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private FragmentStateAdapter pagerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        viewPager = findViewById(R.id.view_pager_wizard);
        pagerAdapter = new WizardPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setUserInputEnabled(false);
        TabLayout tabLayout = findViewById(R.id.tab_layout_wizard);
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
        }).attach();
    }

    public void navigateToNextStep() {
        int currentItem = viewPager.getCurrentItem();
        if (currentItem < pagerAdapter.getItemCount() - 1) {
            viewPager.setCurrentItem(currentItem + 1);
        }
    }
    
    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
    }

    private static class WizardPagerAdapter extends FragmentStateAdapter {
        public WizardPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 1:
                    return new ProgressFragment();
                case 2:
                    return new CompletionFragment();
                case 0:
                default:
                    return new WelcomeFragment();
            }
        }

        @Override
        public int getItemCount() {
            return 3;
        }
    }
}
