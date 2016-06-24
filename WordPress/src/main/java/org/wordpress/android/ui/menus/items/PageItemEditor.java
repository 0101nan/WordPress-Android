package org.wordpress.android.ui.menus.items;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.SearchView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.MenuItemTable;
import org.wordpress.android.models.MenuItemModel;
import org.wordpress.android.models.PostsListPost;
import org.wordpress.android.models.PostsListPostList;
import org.wordpress.android.ui.posts.services.PostEvents;
import org.wordpress.android.ui.posts.services.PostUpdateService;
import org.wordpress.android.widgets.RadioButtonListView;
import org.wordpress.android.widgets.RadioButtonListView.RadioButtonListAdapter;
import org.wordpress.android.widgets.WPTextView;

import java.util.ArrayList;
import java.util.List;

import de.greenrobot.event.EventBus;

/**
 */
public class PageItemEditor extends BaseMenuItemEditor implements SearchView.OnQueryTextListener {
    private final int mLocalBlogId;
    private final List<String> mAllPageTitles;
    private final List<String> mFilteredPageTitles;
    private PostsListPostList mAllPages;
    private PostsListPostList mFilteredPages;

    private RadioButtonListView mPageListView;

    public PageItemEditor(Context context) {
        this(context, null);
    }

    public PageItemEditor(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mLocalBlogId = WordPress.getCurrentLocalTableBlogId();
        mAllPageTitles = new ArrayList<>();
        mFilteredPageTitles = new ArrayList<>();
        loadPages();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        EventBus.getDefault().register(this);
        fetchPages();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);

        ((SearchView) child.findViewById(R.id.single_select_search_view)).setOnQueryTextListener(this);
        mPageListView = (RadioButtonListView) child.findViewById(R.id.single_select_list_view);

        WPTextView emptyTextView = (WPTextView) child.findViewById(R.id.empty_list_view);
        emptyTextView.setText(getContext().getString(R.string.menu_item_type_page_empty_list));
        mPageListView.setEmptyView(emptyTextView);

        mPageListView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mWorkingItem.name = mPageListView.getSelectedItem().toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    public int getLayoutRes() {
        return R.layout.page_menu_item_edit_view;
    }

    @Override
    public int getNameEditTextRes() {
        return R.id.menu_item_title_edit;
    }


    @Override
    public void setMenuItem(MenuItemModel menuItem) {
        super.setMenuItem(menuItem);
        if (!TextUtils.isEmpty(menuItem.name)) {
            setSelection(menuItem.contentId);
        }
    }

    private void setSelection(long contentId) {
        for (int i=0; i < mFilteredPages.size(); i++) {
            PostsListPost post = mFilteredPages.get(i);
            String remoteId = post.getRemotePostId();
            if (remoteId != null && Long.valueOf(remoteId) == contentId){
                mPageListView.setSelection(i);
            }
        }
    }

    public boolean shouldEdit() {
        return false;
    }

    @Override
    public MenuItemModel getMenuItem() {
        MenuItemModel menuItem = super.getMenuItem();
        fillData(menuItem);
        return menuItem;
    }

    @Override
    public void onSave() {
        if (getMenuItem() != null && shouldEdit()) MenuItemTable.saveMenuItem(getMenuItem());
    }

    @Override
    public void onDelete() {
        if (getMenuItem() != null && shouldEdit()) MenuItemTable.deleteMenuItem(getMenuItem().itemId);
    }

    //
    // SearchView query callbacks
    //
    @Override
    public boolean onQueryTextSubmit(String query) {
        filterAdapter(query);
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        filterAdapter(newText);
        return true;
    }

    @SuppressWarnings("unused")
    public void onEventMainThread(PostEvents.RequestPosts event) {
        // update page list with changes from remote
        if (event.getBlogId() == mLocalBlogId) {
            refreshFilteredPages("");
            refreshAdapter();

            MenuItemModel item = super.getMenuItem();
            if (item != null) {
                //super.getMenuItem() called on purpose to avoid any processing, we just want the working item
                setSelection(item.contentId);
            }
        }
    }

    /**
     * Loads locally stored pages
     */
    private void loadPages() {
        mAllPages = WordPress.wpDB.getPostsListPosts(WordPress.getCurrentLocalTableBlogId(), true);
        mFilteredPages = new PostsListPostList();
        for (PostsListPost post : mAllPages) {
            mFilteredPages.add(post);
            mAllPageTitles.add(post.getTitle());
            mFilteredPageTitles.add(post.getTitle());
        }
        refreshAdapter();
    }


    /**
     * Fetch remote pages for blog
     */
    private void fetchPages() {
        PostUpdateService.startServiceForBlog(getContext(), mLocalBlogId, true, false);
    }

    private void filterAdapter(String filter) {
        if (mPageListView == null) return;
        refreshFilteredPages(filter);
        refreshAdapter();
    }

    private void refreshAdapter() {
        if (mPageListView != null) {
            mPageListView.setAdapter(new RadioButtonListAdapter(getContext(), mFilteredPageTitles));
        }
    }

    private void refreshFilteredPages(String filter) {
        mFilteredPageTitles.clear();
        mFilteredPages.clear();
        String upperFiler = filter.toUpperCase();
        for (int i = 0; i < mAllPageTitles.size(); i++) {
            String s = mAllPageTitles.get(i);
            if (s.toUpperCase().contains(upperFiler)) {
                mFilteredPageTitles.add(s);
                mFilteredPages.add(mAllPages.get(i));
            }
        }
    }

    private void fillData(@NonNull MenuItemModel menuItem) {
        //check selected item in array and set selected
        /*
            "type": "page",
			"type_family": "post_type",
			"type_label": "Page",
			* */
        menuItem.type = MenuItemEditorFactory.ITEM_TYPE.PAGE.name().toLowerCase(); //default type: POST
        menuItem.typeFamily = "post_type";
        menuItem.typeLabel = MenuItemEditorFactory.ITEM_TYPE.PAGE.name();

        PostsListPost post = mFilteredPages.get(mPageListView.getCheckedItemPosition());
        if (post != null && post.getRemotePostId() != null) {
            menuItem.contentId = Long.valueOf(post.getRemotePostId());
        }
    }

}
