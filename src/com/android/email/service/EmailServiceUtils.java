/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.service;

import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Service;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.SyncState;
import android.provider.ContactsContract;
import android.provider.SyncStateContract;

import com.android.email.R;
import com.android.emailcommon.Api;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.service.SyncWindow;
import com.android.mail.utils.LogUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility functions for EmailService support.
 */
public class EmailServiceUtils {
    private static final ArrayList<EmailServiceInfo> sServiceList = Lists.newArrayList();
    private static final Map<String, EmailServiceInfo> sServiceMap = Maps.newHashMap();

    /**
     * Starts an EmailService by protocol
     */
    public static void startService(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info != null && info.intentAction != null) {
            context.startService(new Intent(info.intentAction));
        }
    }

    /**
     * Starts all remote services
     */
    public static void startRemoteServices(Context context) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                context.startService(new Intent(info.intentAction));
            }
        }
    }

    /**
     * Returns whether or not remote services are present on device
     */
    public static boolean areRemoteServicesInstalled(Context context) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts all remote services
     */
    public static void setRemoteServicesLogging(Context context, int debugBits) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                EmailServiceProxy service =
                        EmailServiceUtils.getService(context, null, info.protocol);
                if (service != null) {
                    try {
                        service.setLogging(debugBits);
                    } catch (RemoteException e) {
                        // Move along, nothing to see
                    }
                }
            }
        }
    }

    /**
     * Determine if the EmailService is available
     */
    public static boolean isServiceAvailable(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info == null) return false;
        if (info.klass != null) return true;
        return new EmailServiceProxy(context, info.intentAction, null).test();
    }

    /**
     * For a given account id, return a service proxy if applicable, or null.
     *
     * @param accountId the message of interest
     * @result service proxy, or null if n/a
     */
    public static EmailServiceProxy getServiceForAccount(Context context,
            IEmailServiceCallback callback, long accountId) {
        return getService(context, callback, Account.getProtocol(context, accountId));
    }

    /**
     * Holder of service information (currently just name and class/intent); if there is a class
     * member, this is a (local, i.e. same process) service; otherwise, this is a remote service
     */
    public static class EmailServiceInfo {
        public String protocol;
        public String name;
        public String accountType;
        Class<? extends Service> klass;
        String intentAction;
        public int port;
        public int portSsl;
        public boolean defaultSsl;
        public boolean offerTls;
        public boolean offerCerts;
        public boolean usesSmtp;
        public boolean offerLocalDeletes;
        public int defaultLocalDeletes;
        public boolean offerPrefix;
        public boolean usesAutodiscover;
        public boolean offerLookback;
        public int defaultLookback;
        public boolean syncChanges;
        public boolean syncContacts;
        public boolean syncCalendar;
        public boolean offerAttachmentPreload;
        public CharSequence[] syncIntervalStrings;
        public CharSequence[] syncIntervals;
        public int defaultSyncInterval;
        public String inferPrefix;
        public boolean offerLoadMore;
        public boolean requiresSetup;
        public boolean hide;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Protocol: ");
            sb.append(protocol);
            sb.append(", ");
            sb.append(klass != null ? "Local" : "Remote");
            sb.append(" , Account Type: ");
            sb.append(accountType);
            return sb.toString();
        }
    }

    public static EmailServiceProxy getService(Context context, IEmailServiceCallback callback,
            String protocol) {
        EmailServiceInfo info = null;
        // Handle the degenerate case here (account might have been deleted)
        if (protocol != null) {
            info = getServiceInfo(context, protocol);
        }
        if (info == null) {
            LogUtils.w(Logging.LOG_TAG, "Returning NullService for " + protocol);
            return new EmailServiceProxy(context, NullService.class, null);
        } else  {
            return getServiceFromInfo(context, callback, info);
        }
    }

    public static EmailServiceProxy getServiceFromInfo(Context context,
            IEmailServiceCallback callback, EmailServiceInfo info) {
        if (info.klass != null) {
            return new EmailServiceProxy(context, info.klass, callback);
        } else {
            return new EmailServiceProxy(context, info.intentAction, callback);
        }
    }

    public static EmailServiceInfo getServiceInfoForAccount(Context context, long accountId) {
        String protocol = Account.getProtocol(context, accountId);
        return getServiceInfo(context, protocol);
    }

    public static EmailServiceInfo getServiceInfo(Context context, String protocol) {
        if (sServiceList.isEmpty()) {
            findServices(context);
        }
        return sServiceMap.get(protocol);
    }

    public static List<EmailServiceInfo> getServiceInfoList(Context context) {
        synchronized(sServiceList) {
            if (sServiceList.isEmpty()) {
                findServices(context);
            }
            return sServiceList;
        }
    }

    private static void finishAccountManagerBlocker(AccountManagerFuture<?> future) {
        try {
            // Note: All of the potential errors are simply logged
            // here, as there is nothing to actually do about them.
            future.getResult();
        } catch (OperationCanceledException e) {
            LogUtils.w(Logging.LOG_TAG, e.toString());
        } catch (AuthenticatorException e) {
            LogUtils.w(Logging.LOG_TAG, e.toString());
        } catch (IOException e) {
            LogUtils.w(Logging.LOG_TAG, e.toString());
        }
    }

    /**
     * Add an account to the AccountManager.
     * @param context Our {@link Context}.
     * @param account The {@link Account} we're adding.
     * @param email Whether the user wants to sync email on this account.
     * @param calendar Whether the user wants to sync calendar on this account.
     * @param contacts Whether the user wants to sync contacts on this account.
     * @param callback A callback for when the AccountManager is done.
     * @return The result of {@link AccountManager#addAccount}.
     */
    public static AccountManagerFuture<Bundle> setupAccountManagerAccount(final Context context,
            final Account account, final boolean email, final boolean calendar,
            final boolean contacts, final AccountManagerCallback<Bundle> callback) {
        final Bundle options = new Bundle(5);
        final HostAuth hostAuthRecv =
                HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        if (hostAuthRecv == null) {
            return null;
        }
        // Set up username/password
        options.putString(EasAuthenticatorService.OPTIONS_USERNAME, account.mEmailAddress);
        options.putString(EasAuthenticatorService.OPTIONS_PASSWORD, hostAuthRecv.mPassword);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CONTACTS_SYNC_ENABLED, contacts);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CALENDAR_SYNC_ENABLED, calendar);
        options.putBoolean(EasAuthenticatorService.OPTIONS_EMAIL_SYNC_ENABLED, email);
        final EmailServiceInfo info = getServiceInfo(context, hostAuthRecv.mProtocol);
        return AccountManager.get(context).addAccount(info.accountType, null, null, options, null,
                callback, null);
    }

    public static void updateAccountManagerType(Context context,
            android.accounts.Account amAccount, final Map<String, String> protocolMap) {
        final ContentResolver resolver = context.getContentResolver();
        final Cursor c = resolver.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                AccountColumns.EMAIL_ADDRESS + "=?", new String[] { amAccount.name }, null);
        // That's odd, isn't it?
        if (c == null) return;
        try {
            if (c.moveToNext()) {
                // Get the EmailProvider Account/HostAuth
                final Account account = new Account();
                account.restore(c);
                final HostAuth hostAuth =
                        HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
                if (hostAuth == null) {
                    return;
                }

                final String newProtocol = protocolMap.get(hostAuth.mProtocol);
                if (newProtocol == null) {
                    // This account doesn't need updating.
                    return;
                }

                LogUtils.w(Logging.LOG_TAG, "Converting " + amAccount.name + " to "
                        + newProtocol);

                final ContentValues accountValues = new ContentValues();
                int oldFlags = account.mFlags;

                // Mark the provider account incomplete so it can't get reconciled away
                account.mFlags |= Account.FLAGS_INCOMPLETE;
                accountValues.put(AccountColumns.FLAGS, account.mFlags);
                final Uri accountUri = ContentUris.withAppendedId(Account.CONTENT_URI, account.mId);
                resolver.update(accountUri, accountValues, null, null);

                // Change the HostAuth to reference the new protocol; this has to be done before
                // trying to create the AccountManager account (below)
                final ContentValues hostValues = new ContentValues();
                hostValues.put(HostAuth.PROTOCOL, newProtocol);
                resolver.update(ContentUris.withAppendedId(HostAuth.CONTENT_URI, hostAuth.mId),
                        hostValues, null, null);
                LogUtils.w(Logging.LOG_TAG, "Updated HostAuths");

                try {
                    // Get current settings for the existing AccountManager account
                    boolean email = ContentResolver.getSyncAutomatically(amAccount,
                            EmailContent.AUTHORITY);
                    if (!email) {
                        // Try our old provider name
                        email = ContentResolver.getSyncAutomatically(amAccount,
                                "com.android.email.provider");
                    }
                    final boolean contacts = ContentResolver.getSyncAutomatically(amAccount,
                            ContactsContract.AUTHORITY);
                    final boolean calendar = ContentResolver.getSyncAutomatically(amAccount,
                            CalendarContract.AUTHORITY);
                    LogUtils.w(Logging.LOG_TAG, "Email: " + email + ", Contacts: " + contacts + ","
                            + " Calendar: " + calendar);

                    // Get sync keys for calendar/contacts
                    final String amName = amAccount.name;
                    final String oldType = amAccount.type;
                    ContentProviderClient client = context.getContentResolver()
                            .acquireContentProviderClient(CalendarContract.CONTENT_URI);
                    byte[] calendarSyncKey = null;
                    try {
                        calendarSyncKey = SyncStateContract.Helpers.get(client,
                                asCalendarSyncAdapter(SyncState.CONTENT_URI, amName, oldType),
                                new android.accounts.Account(amName, oldType));
                    } catch (RemoteException e) {
                        LogUtils.w(Logging.LOG_TAG, "Get calendar key FAILED");
                    } finally {
                        client.release();
                    }
                    client = context.getContentResolver()
                            .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
                    byte[] contactsSyncKey = null;
                    try {
                        contactsSyncKey = SyncStateContract.Helpers.get(client,
                                ContactsContract.SyncState.CONTENT_URI,
                                new android.accounts.Account(amName, oldType));
                    } catch (RemoteException e) {
                        LogUtils.w(Logging.LOG_TAG, "Get contacts key FAILED");
                    } finally {
                        client.release();
                    }
                    if (calendarSyncKey != null) {
                        LogUtils.w(Logging.LOG_TAG, "Got calendar key: "
                                + new String(calendarSyncKey));
                    }
                    if (contactsSyncKey != null) {
                        LogUtils.w(Logging.LOG_TAG, "Got contacts key: "
                                + new String(contactsSyncKey));
                    }

                    // Set up a new AccountManager account with new type and old settings
                    AccountManagerFuture<?> amFuture = setupAccountManagerAccount(context, account,
                            email, calendar, contacts, null);
                    finishAccountManagerBlocker(amFuture);
                    LogUtils.w(Logging.LOG_TAG, "Created new AccountManager account");

                    // Delete the AccountManager account
                    amFuture = AccountManager.get(context)
                            .removeAccount(amAccount, null, null);
                    finishAccountManagerBlocker(amFuture);
                    LogUtils.w(Logging.LOG_TAG, "Deleted old AccountManager account");

                    // Restore sync keys for contacts/calendar
                    // TODO: Clean up how we determine the type.
                    final String accountType = protocolMap.get(hostAuth.mProtocol + "_type");
                    if (accountType != null &&
                            calendarSyncKey != null && calendarSyncKey.length != 0) {
                        client = context.getContentResolver()
                                .acquireContentProviderClient(CalendarContract.CONTENT_URI);
                        try {
                            SyncStateContract.Helpers.set(client,
                                    asCalendarSyncAdapter(SyncState.CONTENT_URI, amName,
                                            accountType),
                                    new android.accounts.Account(amName, accountType),
                                    calendarSyncKey);
                            LogUtils.w(Logging.LOG_TAG, "Set calendar key...");
                        } catch (RemoteException e) {
                            LogUtils.w(Logging.LOG_TAG, "Set calendar key FAILED");
                        } finally {
                            client.release();
                        }
                    }
                    if (accountType != null &&
                            contactsSyncKey != null && contactsSyncKey.length != 0) {
                        client = context.getContentResolver()
                                .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
                        try {
                            SyncStateContract.Helpers.set(client,
                                    ContactsContract.SyncState.CONTENT_URI,
                                    new android.accounts.Account(amName, accountType),
                                    contactsSyncKey);
                            LogUtils.w(Logging.LOG_TAG, "Set contacts key...");
                        } catch (RemoteException e) {
                            LogUtils.w(Logging.LOG_TAG, "Set contacts key FAILED");
                        }
                    }

                    // That's all folks!
                    LogUtils.w(Logging.LOG_TAG, "Account update completed.");
                } finally {
                    // Clear the incomplete flag on the provider account
                    accountValues.put(AccountColumns.FLAGS, oldFlags);
                    resolver.update(accountUri, accountValues, null, null);
                    LogUtils.w(Logging.LOG_TAG, "[Incomplete flag cleared]");
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Parse services.xml file to find our available email services
     */
    @SuppressWarnings("unchecked")
    private static synchronized void findServices(Context context) {
        try {
            final Resources res = context.getResources();
            final XmlResourceParser xml = res.getXml(R.xml.services);
            int xmlEventType;
            // walk through senders.xml file.
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG &&
                        "emailservice".equals(xml.getName())) {
                    final EmailServiceInfo info = new EmailServiceInfo();
                    final TypedArray ta = res.obtainAttributes(xml, R.styleable.EmailServiceInfo);
                    info.protocol = ta.getString(R.styleable.EmailServiceInfo_protocol);
                    info.accountType = ta.getString(R.styleable.EmailServiceInfo_accountType);
                    info.name = ta.getString(R.styleable.EmailServiceInfo_name);
                    info.hide = ta.getBoolean(R.styleable.EmailServiceInfo_hide, false);
                    final String klass = ta.getString(R.styleable.EmailServiceInfo_serviceClass);
                    info.intentAction = ta.getString(R.styleable.EmailServiceInfo_intent);
                    info.defaultSsl = ta.getBoolean(R.styleable.EmailServiceInfo_defaultSsl, false);
                    info.port = ta.getInteger(R.styleable.EmailServiceInfo_port, 0);
                    info.portSsl = ta.getInteger(R.styleable.EmailServiceInfo_portSsl, 0);
                    info.offerTls = ta.getBoolean(R.styleable.EmailServiceInfo_offerTls, false);
                    info.offerCerts = ta.getBoolean(R.styleable.EmailServiceInfo_offerCerts, false);
                    info.offerLocalDeletes =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerLocalDeletes, false);
                    info.defaultLocalDeletes =
                        ta.getInteger(R.styleable.EmailServiceInfo_defaultLocalDeletes,
                                Account.DELETE_POLICY_ON_DELETE);
                    info.offerPrefix =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerPrefix, false);
                    info.usesSmtp = ta.getBoolean(R.styleable.EmailServiceInfo_usesSmtp, false);
                    info.usesAutodiscover =
                        ta.getBoolean(R.styleable.EmailServiceInfo_usesAutodiscover, false);
                    info.offerLookback =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerLookback, false);
                    info.defaultLookback =
                        ta.getInteger(R.styleable.EmailServiceInfo_defaultLookback,
                                SyncWindow.SYNC_WINDOW_3_DAYS);
                    info.syncChanges =
                        ta.getBoolean(R.styleable.EmailServiceInfo_syncChanges, false);
                    info.syncContacts =
                        ta.getBoolean(R.styleable.EmailServiceInfo_syncContacts, false);
                    info.syncCalendar =
                        ta.getBoolean(R.styleable.EmailServiceInfo_syncCalendar, false);
                    info.offerAttachmentPreload =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerAttachmentPreload, false);
                    info.syncIntervalStrings =
                        ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervalStrings);
                    info.syncIntervals =
                        ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervals);
                    info.defaultSyncInterval =
                        ta.getInteger(R.styleable.EmailServiceInfo_defaultSyncInterval, 15);
                    info.inferPrefix = ta.getString(R.styleable.EmailServiceInfo_inferPrefix);
                    info.offerLoadMore =
                            ta.getBoolean(R.styleable.EmailServiceInfo_offerLoadMore, false);
                    info.requiresSetup =
                            ta.getBoolean(R.styleable.EmailServiceInfo_requiresSetup, false);

                    // Must have either "class" (local) or "intent" (remote)
                    if (klass != null) {
                        try {
                            info.klass = (Class<? extends Service>) Class.forName(klass);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(
                                    "Class not found in service descriptor: " + klass);
                        }
                    }
                    if (info.klass == null && info.intentAction == null) {
                        throw new IllegalStateException(
                                "No class or intent action specified in service descriptor");
                    }
                    if (info.klass != null && info.intentAction != null) {
                        throw new IllegalStateException(
                                "Both class and intent action specified in service descriptor");
                    }
                    sServiceList.add(info);
                    sServiceMap.put(info.protocol, info);
                }
            }
        } catch (XmlPullParserException e) {
            // ignore
        } catch (IOException e) {
            // ignore
        }
    }

    private static Uri asCalendarSyncAdapter(Uri uri, String account, String accountType) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType).build();
    }

    /**
     * A no-op service that can be returned for non-existent/null protocols
     */
    class NullService implements IEmailService {
        @Override
        public IBinder asBinder() {
            return null;
        }

        @Override
        public Bundle validate(HostAuth hostauth) throws RemoteException {
            return null;
        }

        @Override
        public void startSync(long mailboxId, boolean userRequest, int deltaMessageCount)
                throws RemoteException {
        }

        @Override
        public void stopSync(long mailboxId) throws RemoteException {
        }

        @Override
        public void loadMore(long messageId) throws RemoteException {
        }

        @Override
        public void loadAttachment(final IEmailServiceCallback cb, final long attachmentId,
                final boolean background) throws RemoteException {
        }

        @Override
        public void updateFolderList(long accountId) throws RemoteException {
        }

        @Override
        public boolean createFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean renameFolder(long accountId, String oldName, String newName)
                throws RemoteException {
            return false;
        }

        @Override
        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
        }

        @Override
        public void setLogging(int on) throws RemoteException {
        }

        @Override
        public void hostChanged(long accountId) throws RemoteException {
        }

        @Override
        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return null;
        }

        @Override
        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
        }

        @Override
        public void deleteAccountPIMData(final String emailAddress) throws RemoteException {
        }

        @Override
        public int getApiLevel() throws RemoteException {
            return Api.LEVEL;
        }

        @Override
        public int searchMessages(long accountId, SearchParams params, long destMailboxId)
                throws RemoteException {
            return 0;
        }

        @Override
        public void sendMail(long accountId) throws RemoteException {
        }

        @Override
        public void serviceUpdated(String emailAddress) throws RemoteException {
        }

        @Override
        public int getCapabilities(Account acct) throws RemoteException {
            return 0;
        }
    }
}
