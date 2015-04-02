package org.wordpress.android.ui;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.ui.posts.PostsActivity;
import org.wordpress.android.ui.prefs.SettingsActivity;
import org.wordpress.android.ui.stats.StatsActivity;
import org.wordpress.android.widgets.FloatingActionButton;

/**
 * placeholder for main activity tab fragments that don't exist yet
 */

public class DummyFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dummy_fragment, container, false);

        final TextView txtSettings = (TextView) view.findViewById(R.id.btn_settings);
        txtSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ActivityLauncher.viewSettings(getActivity());
            }
        });

        final TextView txtStats = (TextView) view.findViewById(R.id.btn_stats);
        txtStats.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), StatsActivity.class);
                intent.putExtra(StatsActivity.ARG_LOCAL_TABLE_BLOG_ID, WordPress.getCurrentBlog().getLocalTableBlogId());
                getActivity().startActivity(intent);
            }
        });

        final TextView txtPosts = (TextView) view.findViewById(R.id.btn_posts);
        txtPosts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(getActivity(), PostsActivity.class);
                getActivity().startActivity(intent);
            }
        });

        final TextView txtSitePicker = (TextView) view.findViewById(R.id.btn_site_picker);
        txtSitePicker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ActivityLauncher.showSitePickerForResult(getActivity(), false);
            }
        });

        final FloatingActionButton fab = (FloatingActionButton) view.findViewById(R.id.fab_button);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ActivityLauncher.addNewBlogPostOrPage(getActivity(), WordPress.getCurrentBlog(), false);
            }
        });

        return view;
    }

}
