package org.wordpress.android.ui.posts.services;

import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.analytics.AnalyticsTracker.Stat;
import org.wordpress.android.fluxc.Dispatcher;
import org.wordpress.android.fluxc.generated.PostActionBuilder;
import org.wordpress.android.fluxc.model.PostModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.model.post.PostStatus;
import org.wordpress.android.fluxc.store.PostStore.OnPostUploaded;
import org.wordpress.android.fluxc.store.PostStore.RemotePostPayload;
import org.wordpress.android.fluxc.store.SiteStore;
import org.wordpress.android.ui.notifications.ShareAndDismissNotificationReceiver;
import org.wordpress.android.ui.posts.PostsListActivity;
import org.wordpress.android.ui.posts.services.PostEvents.PostUploadStarted;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.util.AnalyticsUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.CrashlyticsUtils;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.ImageUtils;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.StringUtils;
import org.wordpress.android.util.SystemServiceFactory;
import org.wordpress.android.util.WPMeShortlinks;
import org.wordpress.android.util.helpers.MediaFile;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlrpc.android.ApiHelper.Method;
import org.xmlrpc.android.XMLRPCClient;
import org.xmlrpc.android.XMLRPCClientInterface;
import org.xmlrpc.android.XMLRPCException;
import org.xmlrpc.android.XMLRPCFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;

public class PostUploadService extends Service {
    private static Context mContext;
    private static final ArrayList<PostModel> mPostsList = new ArrayList<>();
    private static PostModel mCurrentUploadingPost = null;
    private static boolean mUseLegacyMode;
    private UploadPostTask mCurrentTask = null;

    @Inject Dispatcher mDispatcher;
    @Inject SiteStore mSiteStore;

    public static void addPostToUpload(PostModel currentPost) {
        synchronized (mPostsList) {
            mPostsList.add(currentPost);
        }
    }

    public static void setLegacyMode(boolean enabled) {
        mUseLegacyMode = enabled;
    }

    /*
     * returns true if the passed post is either uploading or waiting to be uploaded
     */
    public static boolean isPostUploading(PostModel post) {
        // first check the currently uploading post
        if (mCurrentUploadingPost != null && mCurrentUploadingPost.getId() == post.getId()) {
            return true;
        }
        // then check the list of posts waiting to be uploaded
        if (mPostsList.size() > 0) {
            synchronized (mPostsList) {
                for (PostModel queuedPost : mPostsList) {
                    if (queuedPost.getId() == post.getId()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ((WordPress) getApplication()).component().inject(this);
        mDispatcher.register(this);
        mContext = this.getApplicationContext();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Cancel current task, it will reset post from "uploading" to "local draft"
        if (mCurrentTask != null) {
            AppLog.d(T.POSTS, "cancelling current upload task");
            mCurrentTask.cancel(true);
        }
        mDispatcher.unregister(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (mPostsList) {
            if (mPostsList.size() == 0 || mContext == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        uploadNextPost();
        // We want this service to continue running until it is explicitly stopped, so return sticky.
        return START_STICKY;
    }

    private void uploadNextPost() {
        synchronized (mPostsList) {
            if (mCurrentTask == null) { //make sure nothing is running
                mCurrentUploadingPost = null;
                if (mPostsList.size() > 0) {
                    mCurrentUploadingPost = mPostsList.remove(0);
                    mCurrentTask = new UploadPostTask();
                    mCurrentTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, mCurrentUploadingPost);
                } else {
                    stopSelf();
                }
            }
        }
    }

    private void postUploaded() {
        synchronized (mPostsList) {
            mCurrentTask = null;
            mCurrentUploadingPost = null;
        }
        uploadNextPost();
    }

    private class UploadPostTask extends AsyncTask<PostModel, Boolean, Boolean> {
        private PostModel mPost;
        private SiteModel mSite;
        private PostUploadNotifier mPostUploadNotifier;

        private String mErrorMessage = "";
        private boolean mIsMediaError = false;
        private int featuredImageID = -1;
        private XMLRPCClientInterface mClient;

        // True when the post goes from draft or local draft to published status
        boolean mIsFirstPublishing = false;

        // Used when the upload succeed
        private Bitmap mLatestIcon;

        // Used for analytics
        private boolean mHasImage, mHasVideo, mHasCategory;

        @Override
        protected void onPostExecute(Boolean postUploadedSuccessfully) {
            if (postUploadedSuccessfully) {
                // TODO: MediaStore?
                //WordPress.wpDB.deleteMediaFilesForPost(mPost);
                mPostUploadNotifier.cancelNotification();
                mPostUploadNotifier.updateNotificationSuccess(mPost, mLatestIcon, mIsFirstPublishing);
            } else {
                mPostUploadNotifier.updateNotificationError(mErrorMessage, mIsMediaError, mPost.isPage());
            }

            postUploaded();
        }

        @Override
        protected void onCancelled(Boolean aBoolean) {
            super.onCancelled(aBoolean);
            // mPostUploadNotifier and mPost can be null if onCancelled is called before doInBackground
            if (mPostUploadNotifier != null && mPost != null) {
                mPostUploadNotifier.updateNotificationError(mErrorMessage, mIsMediaError, mPost.isPage());
            }
        }

        @Override
        protected Boolean doInBackground(PostModel... posts) {
            mPost = posts[0];

            String postTitle = TextUtils.isEmpty(mPost.getTitle()) ? getString(R.string.untitled) : mPost.getTitle();
            String uploadingPostTitle = String.format(getString(R.string.posting_post), postTitle);
            String uploadingPostMessage = String.format(
                    getString(R.string.sending_content),
                    mPost.isPage() ? getString(R.string.page).toLowerCase() : getString(R.string.post).toLowerCase()
            );
            mPostUploadNotifier = new PostUploadNotifier(mPost, uploadingPostTitle, uploadingPostMessage);

            mSite = mSiteStore.getSiteByLocalId(mPost.getLocalSiteId());
            if (mSite == null) {
                mErrorMessage = mContext.getString(R.string.blog_not_found);
                return false;
            }

            // Create the XML-RPC client
            XMLRPCClientInterface mClient = XMLRPCFactory.instantiate(URI.create(mSite.getXmlRpcUrl()), "", "");
            if (TextUtils.isEmpty(mPost.getStatus())) {
                mPost.setStatus(PostStatus.PUBLISHED.toString());
            }

            // TODO: Implement
            // mPost.setContent(processPostMedia(mPost.getContent()));

            // If media file upload failed, let's stop here and prompt the user
            if (mIsMediaError) {
                return false;
            }

            // Support for legacy editor - images are identified as featured as they're being uploaded with the post
            if (mUseLegacyMode && featuredImageID != -1) {
                mPost.setFeaturedImageId(featuredImageID);
            }

            EventBus.getDefault().post(new PostUploadStarted(mPost.getLocalSiteId()));

            RemotePostPayload payload = new RemotePostPayload(mPost, mSite);
            mDispatcher.dispatch(PostActionBuilder.newPushPostAction(payload));

            // TODO: Fix first publish tracking
            // Check if it's the first publishing before changing post status.
            mIsFirstPublishing = mPost.isLocalDraft() && PostStatus.fromPost(mPost) == PostStatus.PUBLISHED;

            // Track analytics only if the post is newly published
            if (mIsFirstPublishing) {
                trackUploadAnalytics();
            }

            return true;
        }

        private boolean hasGallery() {
            Pattern galleryTester = Pattern.compile("\\[.*?gallery.*?\\]");
            Matcher matcher = galleryTester.matcher(mPost.getContent());
            return matcher.find();
        }

        private void trackUploadAnalytics() {
            // Calculate the words count
            Map<String, Object> properties = new HashMap<String, Object>();
            properties.put("word_count", AnalyticsUtils.getWordCount(mPost.getContent()));

            if (hasGallery()) {
                properties.put("with_galleries", true);
            }
            if (mHasImage) {
                properties.put("with_photos", true);
            }
            if (mHasVideo) {
                properties.put("with_videos", true);
            }
            if (mHasCategory) {
                properties.put("with_categories", true);
            }
            if (!mPost.getTagIdList().isEmpty()) {
                properties.put("with_tags", true);
            }
            properties.put("via_new_editor", AppPrefs.isVisualEditorEnabled());
            AnalyticsUtils.trackWithSiteDetails(Stat.EDITOR_PUBLISHED_POST, mSite, properties);
        }

        /**
         * Finds media in post content, uploads them, and returns the HTML to insert in the post
         */
        private String processPostMedia(String postContent) {
            String imageTagsPattern = "<img[^>]+android-uri\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>";
            Pattern pattern = Pattern.compile(imageTagsPattern);
            Matcher matcher = pattern.matcher(postContent);

            int totalMediaItems = 0;
            List<String> imageTags = new ArrayList<String>();
            while (matcher.find()) {
                imageTags.add(matcher.group());
                totalMediaItems++;
            }

            mPostUploadNotifier.setTotalMediaItems(totalMediaItems);

            int mediaItemCount = 0;
            for (String tag : imageTags) {
                Pattern p = Pattern.compile("android-uri=\"([^\"]+)\"");
                Matcher m = p.matcher(tag);
                if (m.find()) {
                    String imageUri = m.group(1);
                    if (!imageUri.equals("")) {
                        // TODO: MediaStore
                        //MediaFile mediaFile = WordPress.wpDB.getMediaFile(imageUri, mPost);
                        MediaFile mediaFile = new MediaFile();
                        if (mediaFile != null) {
                            // Get image thumbnail for notification icon
                            Bitmap imageIcon = ImageUtils.getWPImageSpanThumbnailFromFilePath(
                                    mContext,
                                    imageUri,
                                    DisplayUtils.dpToPx(mContext, 128)
                            );

                            // Crop the thumbnail to be squared in the center
                            if (imageIcon != null) {
                                int squaredSize = DisplayUtils.dpToPx(mContext, 64);
                                imageIcon = ThumbnailUtils.extractThumbnail(imageIcon, squaredSize, squaredSize);
                                mLatestIcon = imageIcon;
                            }

                            mediaItemCount++;
                            mPostUploadNotifier.setCurrentMediaItem(mediaItemCount);
                            mPostUploadNotifier.updateNotificationIcon(imageIcon);

                            String mediaUploadOutput;
                            if (mediaFile.isVideo()) {
                                mHasVideo = true;
                                mediaUploadOutput = uploadVideo(mediaFile);
                            } else {
                                mHasImage = true;
                                mediaUploadOutput = uploadImage(mediaFile);
                            }

                            if (mediaUploadOutput != null) {
                                postContent = postContent.replace(tag, mediaUploadOutput);
                            } else {
                                postContent = postContent.replace(tag, "");
                                mIsMediaError = true;
                            }
                        }
                    }
                }
            }

            return postContent;
        }

        private String uploadImage(MediaFile mediaFile) {
            AppLog.d(T.POSTS, "uploadImage: " + mediaFile.getFilePath());

            if (mediaFile.getFilePath() == null) {
                return null;
            }

            Uri imageUri = Uri.parse(mediaFile.getFilePath());
            File imageFile = null;
            String mimeType = "", path = "";

            if (imageUri.toString().contains("content:")) {
                String[] projection = new String[]{Images.Media._ID, Images.Media.DATA, Images.Media.MIME_TYPE};

                Cursor cur = mContext.getContentResolver().query(imageUri, projection, null, null, null);
                if (cur != null && cur.moveToFirst()) {
                    int dataColumn = cur.getColumnIndex(Images.Media.DATA);
                    int mimeTypeColumn = cur.getColumnIndex(Images.Media.MIME_TYPE);

                    String thumbData = cur.getString(dataColumn);
                    mimeType = cur.getString(mimeTypeColumn);
                    imageFile = new File(thumbData);
                    path = thumbData;
                    mediaFile.setFilePath(imageFile.getPath());
                }
            } else { // file is not in media library
                path = imageUri.toString().replace("file://", "");
                imageFile = new File(path);
                mediaFile.setFilePath(path);
            }

            // check if the file exists
            if (imageFile == null) {
                mErrorMessage = mContext.getString(R.string.file_not_found);
                return null;
            }

            if (TextUtils.isEmpty(mimeType)) {
                mimeType = MediaUtils.getMediaFileMimeType(imageFile);
            }
            String fileName = MediaUtils.getMediaFileName(imageFile, mimeType);

            // Upload the full size picture if "Original Size" is selected in settings

            Map<String, Object> parameters = new HashMap<String, Object>();
            parameters.put("name", fileName);
            parameters.put("type", mimeType);
            parameters.put("bits", mediaFile);
            parameters.put("overwrite", true);

            String fullSizeUrl = uploadImageFile(parameters, mediaFile, mSite);
            if (fullSizeUrl == null) {
                mErrorMessage = mContext.getString(R.string.error_media_upload);
                return null;
            }

            return mediaFile.getImageHtmlForUrls(fullSizeUrl, null, false);
        }

        private String uploadVideo(MediaFile mediaFile) {
            // create temp file for media upload
            String tempFileName = "wp-" + System.currentTimeMillis();
            try {
                mContext.openFileOutput(tempFileName, Context.MODE_PRIVATE);
            } catch (FileNotFoundException e) {
                mErrorMessage = getResources().getString(R.string.file_error_create);
                return null;
            }

            if (mediaFile.getFilePath() == null) {
                mErrorMessage = mContext.getString(R.string.error_media_upload);
                return null;
            }

            Uri videoUri = Uri.parse(mediaFile.getFilePath());
            File videoFile = null;
            String mimeType = "", xRes = "", yRes = "";

            if (videoUri.toString().contains("content:")) {
                String[] projection = new String[]{Video.Media._ID, Video.Media.DATA, Video.Media.MIME_TYPE,
                        Video.Media.RESOLUTION};
                Cursor cur = mContext.getContentResolver().query(videoUri, projection, null, null, null);

                if (cur != null && cur.moveToFirst()) {
                    int dataColumn = cur.getColumnIndex(Video.Media.DATA);
                    int mimeTypeColumn = cur.getColumnIndex(Video.Media.MIME_TYPE);
                    int resolutionColumn = cur.getColumnIndex(Video.Media.RESOLUTION);

                    mediaFile = new MediaFile();

                    String thumbData = cur.getString(dataColumn);
                    mimeType = cur.getString(mimeTypeColumn);

                    videoFile = new File(thumbData);
                    mediaFile.setFilePath(videoFile.getPath());
                    String resolution = cur.getString(resolutionColumn);
                    if (resolution != null) {
                        String[] resolutions = resolution.split("x");
                        if (resolutions.length >= 2) {
                            xRes = resolutions[0];
                            yRes = resolutions[1];
                        }
                    } else {
                        // Default resolution
                        xRes = "640";
                        yRes = "480";
                    }
                }
            } else { // file is not in media library
                String filePath = videoUri.toString().replace("file://", "");
                mediaFile.setFilePath(filePath);
                videoFile = new File(filePath);
            }

            if (videoFile == null) {
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload);
                return null;
            }

            if (TextUtils.isEmpty(mimeType)) {
                mimeType = MediaUtils.getMediaFileMimeType(videoFile);
            }
            String videoName = MediaUtils.getMediaFileName(videoFile, mimeType);

            // try to upload the video
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("name", videoName);
            m.put("type", mimeType);
            m.put("bits", mediaFile);
            m.put("overwrite", true);

            Object[] params = {1, mSite.getUsername(), mSite.getPassword(), m};

            File tempFile;
            try {
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(videoName);
                tempFile = createTempUploadFile(fileExtension);
            } catch (IOException e) {
                mErrorMessage = getResources().getString(R.string.file_error_create);
                return null;
            }

            Object result = uploadFileHelper(params, tempFile);
            Map<?, ?> resultMap = (HashMap<?, ?>) result;
            if (resultMap != null && resultMap.containsKey("url")) {
                String resultURL = resultMap.get("url").toString();
                if (resultMap.containsKey(MediaFile.VIDEOPRESS_SHORTCODE_ID)) {
                    resultURL = resultMap.get(MediaFile.VIDEOPRESS_SHORTCODE_ID).toString() + "\n";
                } else {
                    resultURL = String.format(
                            "<video width=\"%s\" height=\"%s\" controls=\"controls\"><source src=\"%s\" type=\"%s\" /><a href=\"%s\">Click to view video</a>.</video>",
                            xRes, yRes, resultURL, mimeType, resultURL);
                }

                return resultURL;
            } else {
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload);
                return null;
            }
        }


        private void setUploadPostErrorMessage(Exception e) {
            mErrorMessage = String.format(mContext.getResources().getText(R.string.error_upload).toString(),
                    mPost.isPage() ? mContext.getResources().getText(R.string.page).toString() :
                            mContext.getResources().getText(R.string.post).toString()) + " " + e.getMessage();
            mIsMediaError = false;
            AppLog.e(T.EDITOR, mErrorMessage, e);
        }

        private String uploadImageFile(Map<String, Object> pictureParams, MediaFile mf, SiteModel site) {
            // create temporary upload file
            File tempFile;
            try {
                String fileExtension = MimeTypeMap.getFileExtensionFromUrl(mf.getFileName());
                tempFile = createTempUploadFile(fileExtension);
            } catch (IOException e) {
                mIsMediaError = true;
                mErrorMessage = mContext.getString(R.string.file_not_found);
                return null;
            }

            Object[] params = {1,
                    StringUtils.notNullStr(mSite.getUsername()),
                    StringUtils.notNullStr(mSite.getPassword()),
                    pictureParams};
            Object result = uploadFileHelper(params, tempFile);
            if (result == null) {
                mIsMediaError = true;
                return null;
            }

            Map<?, ?> contentHash = (HashMap<?, ?>) result;
            String pictureURL = contentHash.get("url").toString();

            if (mf.isFeatured()) {
                try {
                    if (contentHash.get("id") != null) {
                        featuredImageID = Integer.parseInt(contentHash.get("id").toString());
                        if (!mf.isFeaturedInPost())
                            return "";
                    }
                } catch (NumberFormatException e) {
                    AppLog.e(T.POSTS, e);
                }
            }

            return pictureURL;
        }

        private Object uploadFileHelper(Object[] params, final File tempFile) {
            // Create listener for tracking upload progress in the notification
            if (mClient instanceof XMLRPCClient) {
                XMLRPCClient xmlrpcClient = (XMLRPCClient) mClient;
                xmlrpcClient.setOnBytesUploadedListener(new XMLRPCClient.OnBytesUploadedListener() {
                    @Override
                    public void onBytesUploaded(long uploadedBytes) {
                        if (tempFile.length() == 0) {
                            return;
                        }
                        float percentage = (uploadedBytes * 100) / tempFile.length();
                        mPostUploadNotifier.updateNotificationProgress(percentage);
                    }
                });
            }

            try {
                return mClient.call(Method.UPLOAD_FILE, params, tempFile);
            } catch (XMLRPCException e) {
                // well formed XML-RPC response from the server, but it's an error. Ok to print the error message
                AppLog.e(T.API, e);
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload) + ": " + e.getMessage();
                return null;
            } catch (IOException e) {
                // I/O-related error. Show a generic connection error message
                AppLog.e(T.API, e);
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload_connection);
                return null;
            } catch (XmlPullParserException e) {
                // XML-RPC response isn't well formed or valid. DO NOT print the real error message
                AppLog.e(T.API, e);
                mErrorMessage = mContext.getResources().getString(R.string.error_media_upload);
                return null;
            } finally {
                // remove the temporary upload file now that we're done with it
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }
    }

    private File createTempUploadFile(String fileExtension) throws IOException {
        return File.createTempFile("wp-", fileExtension, mContext.getCacheDir());
    }

    private class PostUploadNotifier {
        private final NotificationManager mNotificationManager;
        private final Builder mNotificationBuilder;
        private final int mNotificationId;
        private int mNotificationErrorId = 0;
        private int mTotalMediaItems;
        private int mCurrentMediaItem;
        private float mItemProgressSize;

        public PostUploadNotifier(PostModel post, String title, String message) {
            // add the uploader to the notification bar
            mNotificationManager = (NotificationManager) SystemServiceFactory.get(mContext,
                    Context.NOTIFICATION_SERVICE);
            mNotificationBuilder = new Notification.Builder(getApplicationContext());
            mNotificationBuilder.setSmallIcon(android.R.drawable.stat_sys_upload);
            if (title != null) {
                mNotificationBuilder.setContentTitle(title);
            }
            if (message != null) {
                mNotificationBuilder.setContentText(message);
            }
            mNotificationId = (new Random()).nextInt() + post.getLocalSiteId();
            startForeground(mNotificationId, mNotificationBuilder.build());
        }

        public void updateNotificationIcon(Bitmap icon) {
            if (icon != null) {
                mNotificationBuilder.setLargeIcon(icon);
            }
            doNotify(mNotificationId, mNotificationBuilder.build());
        }

        public void cancelNotification() {
            mNotificationManager.cancel(mNotificationId);
        }

        public void updateNotificationSuccess(PostModel post, Bitmap largeIcon, boolean isFirstPublishing) {
            AppLog.d(T.POSTS, "updateNotificationSuccess");

            SiteModel site = mSiteStore.getSiteByLocalId(post.getLocalSiteId());
            String shareableUrl = WPMeShortlinks.getPostShortlink(site, post);
            if (shareableUrl == null && !TextUtils.isEmpty(post.getLink())) {
                    shareableUrl = post.getLink();
            }

            // Notification builder
            Builder notificationBuilder = new Notification.Builder(getApplicationContext());
            String notificationTitle = (String) (post.isPage() ? mContext.getResources().getText(R.string
                    .page_published) : mContext.getResources().getText(R.string.post_published));
            if (!isFirstPublishing) {
                notificationTitle = (String) (post.isPage() ? mContext.getResources().getText(R.string
                        .page_updated) : mContext.getResources().getText(R.string.post_updated));
            }
            notificationBuilder.setSmallIcon(android.R.drawable.stat_sys_upload_done);
            if (largeIcon == null) {
                notificationBuilder.setLargeIcon(BitmapFactory.decodeResource(getApplicationContext().getResources(),
                        R.mipmap.app_icon));
            } else {
                notificationBuilder.setLargeIcon(largeIcon);
            }
            notificationBuilder.setContentTitle(notificationTitle);
            notificationBuilder.setContentText(post.getTitle());
            notificationBuilder.setAutoCancel(true);

            // Tap notification intent (open the post list)
            Intent notificationIntent = new Intent(mContext, PostsListActivity.class);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            notificationIntent.putExtra(PostsListActivity.EXTRA_SELECT_SITE_LOCAL_ID, post.getLocalSiteId());
            notificationIntent.putExtra(PostsListActivity.EXTRA_VIEW_PAGES, post.isPage());
            PendingIntent pendingIntentPost = PendingIntent.getActivity(mContext, 0,
                    notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            notificationBuilder.setContentIntent(pendingIntentPost);

            // Share intent - started if the user tap the share link button - only if the link exist
            long notificationId = getNotificationIdForPost(post);
            if (shareableUrl != null && PostStatus.fromPost(post) == PostStatus.PUBLISHED) {
                Intent shareIntent = new Intent(mContext, ShareAndDismissNotificationReceiver.class);
                shareIntent.putExtra(ShareAndDismissNotificationReceiver.NOTIFICATION_ID_KEY, notificationId);
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareableUrl);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, post.getTitle());
                PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, shareIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT);
                notificationBuilder.addAction(R.drawable.ic_share_white_24dp, getString(R.string.share_action),
                        pendingIntent);
            }
            doNotify(notificationId, notificationBuilder.build());
        }

        private long getNotificationIdForPost(PostModel post) {
            long remotePostId = post.getRemotePostId();
            // We can't use the local table post id here because it can change between first post (local draft) to
            // first edit (post pulled from the server)
            return post.getLocalSiteId() + remotePostId;
        }

        public void updateNotificationError(String mErrorMessage, boolean isMediaError, boolean isPage) {
            AppLog.d(T.POSTS, "updateNotificationError: " + mErrorMessage);

            Builder notificationBuilder = new Notification.Builder(getApplicationContext());
            String postOrPage = (String) (isPage ? mContext.getResources().getText(R.string.page_id)
                    : mContext.getResources().getText(R.string.post_id));
            Intent notificationIntent = new Intent(mContext, PostsListActivity.class);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            notificationIntent.putExtra(PostsListActivity.EXTRA_VIEW_PAGES, isPage);
            notificationIntent.putExtra(PostsListActivity.EXTRA_ERROR_MSG, mErrorMessage);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0,
                    notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);

            String errorText = mContext.getResources().getText(R.string.upload_failed).toString();
            if (isMediaError) {
                errorText = mContext.getResources().getText(R.string.media) + " "
                        + mContext.getResources().getText(R.string.error);
            }

            notificationBuilder.setSmallIcon(android.R.drawable.stat_notify_error);
            notificationBuilder.setContentTitle((isMediaError) ? errorText :
                    mContext.getResources().getText(R.string.upload_failed));
            notificationBuilder.setContentText((isMediaError) ? mErrorMessage : postOrPage + " " + errorText
                    + ": " + mErrorMessage);
            notificationBuilder.setContentIntent(pendingIntent);
            notificationBuilder.setAutoCancel(true);
            if (mNotificationErrorId == 0) {
                mNotificationErrorId = mNotificationId + (new Random()).nextInt();
            }
            doNotify(mNotificationErrorId, notificationBuilder.build());
        }

        public void updateNotificationProgress(float progress) {
            if (mTotalMediaItems == 0) {
                return;
            }

            // Simple way to show progress of entire post upload
            // Would be better if we could get total bytes for all media items.
            double currentChunkProgress = (mItemProgressSize * progress) / 100;

            if (mCurrentMediaItem > 1) {
                currentChunkProgress += mItemProgressSize * (mCurrentMediaItem - 1);
            }

            mNotificationBuilder.setProgress(100, (int)Math.ceil(currentChunkProgress), false);
            doNotify(mNotificationId, mNotificationBuilder.build());
        }

        private synchronized void doNotify(long id, Notification notification) {
            try {
                mNotificationManager.notify((int) id, notification);
            } catch (RuntimeException runtimeException) {
                CrashlyticsUtils.logException(runtimeException, CrashlyticsUtils.ExceptionType.SPECIFIC,
                        AppLog.T.UTILS, "See issue #2858 / #3966");
                AppLog.d(T.POSTS, "See issue #2858 / #3966; notify failed with:" + runtimeException);
            }
        }

        public void setTotalMediaItems(int totalMediaItems) {
            if (totalMediaItems <= 0) {
                totalMediaItems = 1;
            }

            mTotalMediaItems = totalMediaItems;
            mItemProgressSize = 100.0f / mTotalMediaItems;
        }

        public void setCurrentMediaItem(int currentItem) {
            mCurrentMediaItem = currentItem;

            mNotificationBuilder.setContentText(String.format(getString(R.string.uploading_total), mCurrentMediaItem,
                    mTotalMediaItems));
        }
    }

    @SuppressWarnings("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPostUploaded(OnPostUploaded event) {
        // TODO: Implement
    }
}
