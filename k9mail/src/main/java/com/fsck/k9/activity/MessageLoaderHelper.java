package com.fsck.k9.activity;


import android.app.FragmentManager;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.Loader;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;

import com.fsck.k9.Account;
import com.fsck.k9.Preferences;
import com.fsck.k9.controller.MessagingController;
import com.fsck.k9.controller.MessagingListener;
import com.fsck.k9.helper.RetainFragment;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.MessageViewInfo;
import com.fsck.k9.ui.crypto.MessageCryptoAnnotations;
import com.fsck.k9.ui.crypto.MessageCryptoCallback;
import com.fsck.k9.ui.crypto.MessageCryptoHelper;
import com.fsck.k9.ui.message.LocalMessageExtractorLoader;
import com.fsck.k9.ui.message.LocalMessageLoader;


public class MessageLoaderHelper {
    private static final int LOCAL_MESSAGE_LOADER_ID = 1;
    private static final int DECODE_MESSAGE_LOADER_ID = 2;


    // injected state
    private final Context context;
    private final FragmentManager fragmentManager;
    private final LoaderManager loaderManager;
    @Nullable // may be cleared
    private MessageLoaderCallbacks callback;


    // transient state
    private MessageReference messageReference;
    private Account account;

    private LocalMessage localMessage;
    private MessageCryptoAnnotations messageCryptoAnnotations;

    private MessageCryptoHelper messageCryptoHelper;
    private RetainFragment<MessageCryptoHelper> retainCryptoHelperFragment;


    public MessageLoaderHelper(Context context, LoaderManager loaderManager, FragmentManager fragmentManager,
            @NonNull MessageLoaderCallbacks callback) {
        this.context = context;
        this.loaderManager = loaderManager;
        this.fragmentManager = fragmentManager;
        this.callback = callback;
    }


    // public interface

    @UiThread
    public void asyncStartOrResumeLoadingMessage(MessageReference messageReference) {
        this.messageReference = messageReference;
        this.account = Preferences.getPreferences(context).getAccount(messageReference.getAccountUuid());

        startOrResumeLocalMessageLoader();
    }

    @UiThread
    public void onDestroy() {
        cancelAndClearLocalMessageLoader();
        cancelAndClearDecodeLoader();
        cancelAndClearCryptoOperation();

        callback = null;
    }

    @UiThread
    public void onDestroyChangingConfigurations() {
        if (messageCryptoHelper != null) {
            messageCryptoHelper.detachCallback();
        }

        callback = null;
    }

    @UiThread
    public void restartMessageCryptoProcessing() {
        cancelAndClearCryptoOperation();
        startOrResumeCryptoOperation();
    }

    @UiThread
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        messageCryptoHelper.onActivityResult(requestCode, resultCode, data);
    }

    @UiThread
    public void onClickDownloadCompleteMessage() {
        if (localMessage.isSet(Flag.X_DOWNLOADED_FULL)) {
            return;
        }

        startDownloadingMessageBody(true);
    }


    // load from database

    private void startOrResumeLocalMessageLoader() {
        loaderManager.initLoader(LOCAL_MESSAGE_LOADER_ID, null, localMessageLoaderCallback);
    }

    @UiThread
    private void onLoadMessageFromDatabaseFinished() {
        if (callback == null) {
            throw new IllegalStateException("unexpected call when callback is already detached");
        }

        callback.onMessageLoadFinished(localMessage);

        if (localMessage.isBodyMissing()) {
            startDownloadingMessageBody(false);
            return;
        }

        if (account.isOpenPgpProviderConfigured()) {
            startOrResumeCryptoOperation();
            return;
        }

        startOrResumeDecodeMessage();
    }

    private void onLoadMessageFromDatabaseFailed() {
        if (callback == null) {
            throw new IllegalStateException("unexpected call when callback is already detached");
        }
        callback.onMessageLoadFailed();
    }

    private void cancelAndClearLocalMessageLoader() {
        loaderManager.destroyLoader(LOCAL_MESSAGE_LOADER_ID);
    }

    private LoaderCallbacks<LocalMessage> localMessageLoaderCallback = new LoaderCallbacks<LocalMessage>() {
        @Override
        public Loader<LocalMessage> onCreateLoader(int id, Bundle args) {
            if (id != LOCAL_MESSAGE_LOADER_ID) {
                throw new IllegalStateException("loader id must be message loader id");
            }

            return new LocalMessageLoader(context, MessagingController.getInstance(context), account, messageReference);
        }

        @Override
        public void onLoadFinished(Loader<LocalMessage> loader, LocalMessage message) {
            if (loader.getId() != LOCAL_MESSAGE_LOADER_ID) {
                throw new IllegalStateException("loader id must be message loader id");
            }

            localMessage = message;
            if (message == null) {
                onLoadMessageFromDatabaseFailed();
            } else {
                onLoadMessageFromDatabaseFinished();
            }
            loaderManager.destroyLoader(LOCAL_MESSAGE_LOADER_ID);
        }

        @Override
        public void onLoaderReset(Loader<LocalMessage> loader) {
            if (loader.getId() != LOCAL_MESSAGE_LOADER_ID) {
                throw new IllegalStateException("loader id must be message loader id");
            }
            // Do nothing
        }
    };


    // process with crypto helper

    private void startOrResumeCryptoOperation() {
        retainCryptoHelperFragment =
                RetainFragment.findOrCreate(fragmentManager, "crypto_helper_" + localMessage.hashCode());
        if (retainCryptoHelperFragment.hasData()) {
            messageCryptoHelper = retainCryptoHelperFragment.getData();
        } else {
            messageCryptoHelper = new MessageCryptoHelper(context, account.getOpenPgpProvider());
            retainCryptoHelperFragment.setData(messageCryptoHelper);
        }
        messageCryptoHelper.asyncStartOrResumeProcessingMessage(localMessage, messageCryptoCallback);
    }

    private void cancelAndClearCryptoOperation() {
        if (messageCryptoHelper != null) {
            messageCryptoHelper.cancelIfRunning();
            messageCryptoHelper = null;
        }
        if (retainCryptoHelperFragment != null) {
            retainCryptoHelperFragment.clearAndRemove(fragmentManager);
            retainCryptoHelperFragment = null;
        }
    }

    private MessageCryptoCallback messageCryptoCallback = new MessageCryptoCallback() {
        @Override
        public void onCryptoHelperProgress(int current, int max) {
            if (callback == null) {
                throw new IllegalStateException("unexpected call when callback is already detached");
            }

            callback.setLoadingProgress(current, max);
        }

        @Override
        public void onCryptoOperationsFinished(MessageCryptoAnnotations annotations) {
            if (callback == null) {
                throw new IllegalStateException("unexpected call when callback is already detached");
            }

            messageCryptoAnnotations = annotations;
            startOrResumeDecodeMessage();
        }

        @Override
        public void startPendingIntentForCryptoHelper(IntentSender si, int requestCode, Intent fillIntent,
                int flagsMask, int flagValues, int extraFlags) {
            if (callback == null) {
                throw new IllegalStateException("unexpected call when callback is already detached");
            }

            callback.startIntentSenderForMessageLoaderHelper(si, requestCode, fillIntent,
                    flagsMask, flagValues, extraFlags);
        }
    };


    // decode message

    private void startOrResumeDecodeMessage() {
        loaderManager.initLoader(DECODE_MESSAGE_LOADER_ID, null, decodeMessageLoaderCallback);
    }

    private void onDecodeMessageFinished(MessageViewInfo messageViewInfo) {
        if (callback == null) {
            throw new IllegalStateException("unexpected call when callback is already detached");
        }

        if (messageViewInfo == null) {
            callback.onMessageViewInfoLoadFailed(localMessage);
            return;
        }

        callback.onMessageViewInfoLoadFinished(localMessage, messageViewInfo);
    }

    private void cancelAndClearDecodeLoader() {
        loaderManager.destroyLoader(DECODE_MESSAGE_LOADER_ID);
    }

    private LoaderCallbacks<MessageViewInfo> decodeMessageLoaderCallback = new LoaderCallbacks<MessageViewInfo>() {
        @Override
        public Loader<MessageViewInfo> onCreateLoader(int id, Bundle args) {
            if (id != DECODE_MESSAGE_LOADER_ID) {
                throw new IllegalStateException("loader id must be message decoder id");
            }
            return new LocalMessageExtractorLoader(context, localMessage, messageCryptoAnnotations);
        }

        @Override
        public void onLoadFinished(Loader<MessageViewInfo> loader, MessageViewInfo messageViewInfo) {
            if (loader.getId() != DECODE_MESSAGE_LOADER_ID) {
                throw new IllegalStateException("loader id must be message decoder id");
            }
            onDecodeMessageFinished(messageViewInfo);
        }

        @Override
        public void onLoaderReset(Loader<MessageViewInfo> loader) {
            if (loader.getId() != DECODE_MESSAGE_LOADER_ID) {
                throw new IllegalStateException("loader id must be message decoder id");
            }
            // Do nothing
        }
    };


    // download missing body

    private void startDownloadingMessageBody(boolean downloadComplete) {
        if (downloadComplete) {
            MessagingController.getInstance(context).loadMessageRemote(
                    account, messageReference.getFolderName(), messageReference.getUid(), downloadMessageListener);
        } else {
            MessagingController.getInstance(context).loadMessageRemotePartial(
                    account, messageReference.getFolderName(), messageReference.getUid(), downloadMessageListener);
        }
    }

    private void onMessageDownloadFinished() {
        if (callback == null) {
            return;
        }

        cancelAndClearLocalMessageLoader();
        cancelAndClearDecodeLoader();
        cancelAndClearCryptoOperation();

        startOrResumeLocalMessageLoader();
    }

    private void onDownloadMessageFailed(final Throwable t) {
        if (callback == null) {
            return;
        }

        if (t instanceof IllegalArgumentException) {
            callback.onDownloadErrorMessageNotFound();
        } else {
            callback.onDownloadErrorNetworkError();
        }
    }

    MessagingListener downloadMessageListener = new MessagingListener() {
        @Override
        public void loadMessageRemoteFinished(Account account, String folder, String uid) {
            onMessageDownloadFinished();
        }

        @Override
        public void loadMessageRemoteFailed(Account account, String folder, String uid, final Throwable t) {
            onDownloadMessageFailed(t);
        }
    };


    // callback interface

    public interface MessageLoaderCallbacks {
        void onMessageLoadFinished(LocalMessage message);
        void onMessageLoadFailed();

        void onMessageViewInfoLoadFinished(LocalMessage localMessage, MessageViewInfo messageViewInfo);
        void onMessageViewInfoLoadFailed(LocalMessage localMessage);

        void setLoadingProgress(int current, int max);

        void startIntentSenderForMessageLoaderHelper(IntentSender si, int requestCode, Intent fillIntent, int flagsMask,
                int flagValues, int extraFlags);

        void onDownloadErrorMessageNotFound();
        void onDownloadErrorNetworkError();
    }

}
