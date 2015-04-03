package org.wordpress.android.ui;

import android.app.Fragment;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.support.v7.app.ActionBarActivity;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.models.Blog;
import org.wordpress.android.networking.SelfSignedSSLCertsManager;
import org.wordpress.android.ui.accounts.SignInActivity;
import org.wordpress.android.ui.mysite.MySiteFragment;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.ui.reader.ReaderPostListFragment;
import org.wordpress.android.util.AccountHelper;
import org.wordpress.android.util.AuthenticationDialogUtils;
import org.wordpress.android.util.CoreEvents;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.widgets.SlidingTabLayout;
import org.wordpress.android.widgets.WPMainViewPager;

import de.greenrobot.event.EventBus;

/**
 * Main activity which hosts sites, reader, me and notifications tabs
 */

/*
 * TODO: handle notifications & reader with no wp.com account
 * TODO: notifications tab needs a badge when their are unseen notes
 */

public class WPMainActivity extends ActionBarActivity
    implements ViewPager.OnPageChangeListener
{
    private WPMainViewPager mViewPager;
    private SlidingTabLayout mTabs;
    private WPMainTabAdapter mTabAdapter;

    private int mPreviousPosition = -1;

    private static final String KEY_INITIAL_UPDATE = "initial_update_performed";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(getResources().getColor(R.color.status_bar_tint));
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mViewPager = (WPMainViewPager) findViewById(R.id.viewpager_main);
        mTabAdapter = new WPMainTabAdapter(getFragmentManager());
        mViewPager.setAdapter(mTabAdapter);

        mTabs = (SlidingTabLayout) findViewById(R.id.sliding_tabs);
        mTabs.setSelectedIndicatorColors(getResources().getColor(R.color.tab_indicator));
        mTabs.setDistributeEvenly(true);
        Integer icons[] = {R.drawable.main_tab_sites,
                           R.drawable.main_tab_reader,
                           R.drawable.main_tab_me,
                           R.drawable.main_tab_notifications};
        mTabs.setCustomTabView(R.layout.tab_icon, R.id.tab_icon, R.id.tab_badge, icons);
        mTabs.setViewPager(mViewPager);

        // page change listener must be set on the tab layout rather than the ViewPager
        mTabs.setOnPageChangeListener(this);

        if (savedInstanceState == null) {
            if (showSignInIfRequired()) {
                // return to the tab that was showing the last time
                int position = AppPrefs.getMainTabIndex();
                if (mTabAdapter.isValidPosition(position) && position != mViewPager.getCurrentItem()) {
                    mViewPager.setCurrentItem(position);
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onPageSelected(int position) {
        // remember the index of this page
        AppPrefs.setMainTabIndex(position);

        mTabs.setBadge(mPreviousPosition, false);
        mTabs.setBadge(position, true);

        mPreviousPosition = position;
    }

    @Override
    public void onPageScrollStateChanged(int state) {
        // nop
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        // nop
    }

    @Override
    protected void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case RequestCodes.READER_SUBS:
            case RequestCodes.READER_REBLOG:
                ReaderPostListFragment readerFragment = getReaderListFragment();
                // TODO:
                if (readerFragment != null) {
                    //readerFragment.handleActivityResult(requestCode, resultCode, data);
                }
                break;
            case RequestCodes.ADD_ACCOUNT:
                if (resultCode == RESULT_OK) {
                    WordPress.registerForCloudMessaging(this);
                } else {
                    finish();
                }
                break;
            case RequestCodes.REAUTHENTICATE:
                if (resultCode == RESULT_CANCELED) {
                    showSignIn();
                } else {
                    WordPress.registerForCloudMessaging(this);
                }
                break;
            case RequestCodes.SETTINGS:
                // user returned from settings
                if (showSignInIfRequired()) {
                    WordPress.registerForCloudMessaging(this);
                }
                break;
            case RequestCodes.SITE_PICKER:
                if (resultCode == RESULT_OK && data != null) {
                    int localId = data.getIntExtra(SitePickerActivity.KEY_LOCAL_ID, 0);
                    //String blogId = data.getStringExtra(SitePickerActivity.KEY_BLOG_ID);

                    // when a new blog is picked, set it to the current blog
                    Blog blog = WordPress.setCurrentBlog(localId);
                    WordPress.wpDB.updateLastBlogId(localId);

                    MySiteFragment mySiteFragment = getMySiteFragment();
                    if (mySiteFragment != null) {
                        mySiteFragment.setBlog(blog);
                    }
                }
                break;
        }
    }

   /*
    * displays the sign-in activity if the user isn't logged in - returns true if user
    * is already signed in
    */
    private boolean showSignInIfRequired() {
        if (AccountHelper.isSignedIn()) {
            return true;
        }
        showSignIn();
        return false;
    }

    private void showSignIn() {
        mPreviousPosition = -1;
        startActivityForResult(new Intent(this, SignInActivity.class), RequestCodes.ADD_ACCOUNT);
    }

    /*
     * returns the reader list fragment from the reader tab
     */
    private ReaderPostListFragment getReaderListFragment() {
        Fragment fragment = mTabAdapter.getFragment(WPMainTabAdapter.TAB_READER);
        if (fragment != null && fragment instanceof ReaderPostListFragment) {
            return (ReaderPostListFragment) fragment;
        }
        return null;
    }

    /*
     * returns the my site fragment from the sites tab
     */
    private MySiteFragment getMySiteFragment() {
        Fragment fragment = mTabAdapter.getFragment(WPMainTabAdapter.TAB_SITES);
        if (fragment != null && fragment instanceof MySiteFragment) {
            return (MySiteFragment) fragment;
        }
        return null;
    }

    // Events

    @SuppressWarnings("unused")
    public void onEventMainThread(CoreEvents.UserSignedOut event) {
        showSignIn();
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(CoreEvents.InvalidCredentialsDetected event) {
        AuthenticationDialogUtils.showAuthErrorView(this);
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(CoreEvents.RestApiUnauthorized event) {
        AuthenticationDialogUtils.showAuthErrorView(this);
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(CoreEvents.TwoFactorAuthenticationDetected event) {
        AuthenticationDialogUtils.showAuthErrorView(this);
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(CoreEvents.InvalidSslCertificateDetected event) {
        SelfSignedSSLCertsManager.askForSslTrust(this, null);
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(CoreEvents.LoginLimitDetected event) {
        ToastUtils.showToast(this, R.string.limit_reached, ToastUtils.Duration.LONG);
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(CoreEvents.BlogListChanged event) {
        // TODO: reload blog list if showing
    }
}
