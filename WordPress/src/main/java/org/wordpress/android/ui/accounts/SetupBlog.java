package org.wordpress.android.ui.accounts;

import android.webkit.URLUtil;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest.ErrorListener;
import com.wordpress.rest.RestRequest.Listener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.Constants;
import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.networking.LoginAndFetchBlogListAbstract.Callback;
import org.wordpress.android.networking.LoginAndFetchBlogListWPCom;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.BlogUtils;
import org.wordpress.android.util.JSONUtil;
import org.wordpress.android.util.UrlUtils;
import org.wordpress.android.util.VolleyUtils;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlrpc.android.ApiHelper;
import org.xmlrpc.android.XMLRPCClientInterface;
import org.xmlrpc.android.XMLRPCException;
import org.xmlrpc.android.XMLRPCFactory;
import org.xmlrpc.android.XMLRPCFault;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

public class SetupBlog {
    private String mUsername;
    private String mPassword;
    private String mHttpUsername = "";
    private String mHttpPassword = "";
    private String mXmlrpcUrl;

    private int mErrorMsgId;
    private boolean mIsCustomUrl;
    private String mSelfHostedURL;

    private boolean mHttpAuthRequired;
    private boolean mErroneousSslCertificate;

    public SetupBlog() {
    }

    public int getErrorMsgId() {
        return mErrorMsgId;
    }

    public String getXmlrpcUrl() {
        return mXmlrpcUrl;
    }

    public String getCustomXmlrpcUrl() {
        if (mIsCustomUrl) {
            return mXmlrpcUrl;
        } else {
            return null;
        }
    }

    public void setUsername(String username) {
        mUsername = username;
    }

    public void setPassword(String password) {
        mPassword = password;
    }

    public String getPassword() {
        return mPassword;
    }

    public String getUsername() {
        return mUsername;
    }

    public void setHttpUsername(String httpUsername) {
        mHttpUsername = httpUsername;
    }

    public void setHttpPassword(String httpPassword) {
        mHttpPassword = httpPassword;
    }

    public void setSelfHostedURL(String selfHostedURL) {
        mSelfHostedURL = selfHostedURL;
    }

    public void setHttpAuthRequired(boolean httpAuthRequired) {
        mHttpAuthRequired = httpAuthRequired;
    }

    public boolean isHttpAuthRequired() {
        return mHttpAuthRequired;
    }

    public boolean isErroneousSslCertificates() {
        return mErroneousSslCertificate;
    }

    public boolean isDotComBlog() {
        return mUsername != null && mPassword != null && mSelfHostedURL == null;
    }

    private void handleXmlRpcFault(XMLRPCFault xmlRpcFault) {
        AppLog.e(T.NUX, "XMLRPCFault received from XMLRPC call wp.getUsersBlogs", xmlRpcFault);
        switch (xmlRpcFault.getFaultCode()) {
            case 403:
                mErrorMsgId = R.string.username_or_password_incorrect;
                break;
            case 404:
                mErrorMsgId = R.string.xmlrpc_error;
                break;
            case 425:
                mErrorMsgId = R.string.account_two_step_auth_enabled;
                break;
            default:
                mErrorMsgId = R.string.no_site_error;
                break;
        }
    }

    private void getUsersBlogsRequest(URI uri, boolean isWPCom, Callback callback) {
        if (isWPCom) {
            getUsersBlogsRequestREST(callback);
        } else {
            getUsersBlogsRequestXMLRPC(uri, callback);
        }
    }

    private List<Map<String, Object>> convertJSONObjectToSiteList(JSONObject jsonObject, boolean keepJetpackSites) {
        List<Map<String, Object>> sites = new ArrayList<Map<String, Object>>();
        JSONArray jsonSites = jsonObject.optJSONArray("sites");
        if (jsonSites != null) {
            for (int i = 0; i < jsonSites.length(); i++) {
                JSONObject jsonSite = jsonSites.optJSONObject(i);
                Map<String, Object> site = new HashMap<String, Object>();
                try {
                    // skip if it's a jetpack site and we don't keep them
                    if (jsonSite.getBoolean("jetpack") && !keepJetpackSites) {
                        continue;
                    }
                    site.put("blogName", jsonSite.get("name"));
                    site.put("url", jsonSite.get("URL"));
                    site.put("blogid", jsonSite.get("ID"));
                    site.put("isAdmin", jsonSite.get("user_can_manage"));
                    site.put("isVisible", jsonSite.get("visible"));
                    JSONObject jsonLinks = JSONUtil.getJSONChild(jsonSite, "meta/links");
                    if (jsonLinks != null) {
                        site.put("xmlrpc", jsonLinks.getString("xmlrpc"));
                        sites.add(site);
                    } else {
                        AppLog.e(T.NUX, "xmlrpc links missing from the me/sites REST response");
                    }
                } catch (JSONException e) {
                    AppLog.e(T.NUX, e);
                }
            }
        }
        return sites;
    }

    private void getUsersBlogsRequestREST(final Callback callback) {
        WordPress.getRestClientUtils().get("me/sites", new Listener() {
            @Override
            public void onResponse(JSONObject response) {
                if (response != null) {
                    List<Map<String, Object>> userBlogListReceiver = convertJSONObjectToSiteList(response, false);
                    callback.onSuccess(userBlogListReceiver);
                } else {
                    callback.onSuccess(null);
                }
            }
        }, new ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                JSONObject errorObject = VolleyUtils.volleyErrorToJSON(volleyError);
                callback.onError(LoginAndFetchBlogListWPCom.restLoginErrorToMsgId(errorObject), false, false);
            }
        });
    }

    private void getUsersBlogsRequestXMLRPC(URI uri, final Callback callback) {
        XMLRPCClientInterface client = XMLRPCFactory.instantiate(uri, mHttpUsername, mHttpPassword);
        Object[] params = {mUsername, mPassword};
        try {
            Object[] userBlogs = (Object[]) client.call("wp.getUsersBlogs", params);
            if (userBlogs == null) {
                // Could happen if the returned server response is truncated
                mErrorMsgId = R.string.xmlrpc_error;
                callback.onError(mErrorMsgId, false, false);
                return;
            }
            Arrays.sort(userBlogs, BlogUtils.BlogNameComparator);
            List<Map<String, Object>> userBlogList = new ArrayList<Map<String, Object>>();
            for (Object blog : userBlogs) {
                try {
                    userBlogList.add((Map<String, Object>) blog);
                } catch (ClassCastException e) {
                    AppLog.e(T.NUX, "invalid data received from XMLRPC call wp.getUsersBlogs");
                }
            }
            callback.onSuccess(userBlogList);
        } catch (XmlPullParserException parserException) {
            mErrorMsgId = R.string.xmlrpc_error;
            AppLog.e(T.NUX, "invalid data received from XMLRPC call wp.getUsersBlogs", parserException);
        } catch (XMLRPCFault xmlRpcFault) {
            handleXmlRpcFault(xmlRpcFault);
        } catch (XMLRPCException xmlRpcException) {
            AppLog.e(T.NUX, "XMLRPCException received from XMLRPC call wp.getUsersBlogs", xmlRpcException);
            mErrorMsgId = R.string.no_site_error;
        } catch (SSLHandshakeException e) {
            if (!UrlUtils.getDomainFromUrl(mXmlrpcUrl).endsWith("wordpress.com")) {
                mErroneousSslCertificate = true;
            }
            AppLog.w(T.NUX, "SSLHandshakeException failed. Erroneous SSL certificate detected.");
        } catch (IOException e) {
            AppLog.e(T.NUX, "Exception received from XMLRPC call wp.getUsersBlogs", e);
            mErrorMsgId = R.string.no_site_error;
        }
        callback.onError(mErrorMsgId, isHttpAuthRequired(), isErroneousSslCertificates());
    }

    public void getBlogList(Callback callback) {
        boolean isWPCom = false;
        if (mSelfHostedURL != null && mSelfHostedURL.length() != 0) {
            mXmlrpcUrl = getSelfHostedXmlrpcUrl(mSelfHostedURL);
        } else {
            mXmlrpcUrl = Constants.wpcomXMLRPCURL;
            isWPCom = true;
        }

        if (mXmlrpcUrl == null) {
            if (!mHttpAuthRequired && mErrorMsgId == 0) {
                mErrorMsgId = R.string.no_site_error;
            }
            callback.onError(mErrorMsgId, isHttpAuthRequired(), isErroneousSslCertificates());
            return;
        }

        // Validate the URL found before calling the client. Prevent a crash that can occur
        // during the setup of self-hosted sites.
        URI uri;
        try {
            uri = URI.create(mXmlrpcUrl);
            getUsersBlogsRequest(uri, isWPCom, callback);
        } catch (Exception e) {
            mErrorMsgId = R.string.no_site_error;
            callback.onError(mErrorMsgId, isHttpAuthRequired(), isErroneousSslCertificates());
        }
    }

    private String getRsdUrl(String baseUrl) throws SSLHandshakeException {
        String rsdUrl;
        rsdUrl = ApiHelper.getRSDMetaTagHrefRegEx(baseUrl);
        if (rsdUrl == null) {
            rsdUrl = ApiHelper.getRSDMetaTagHref(baseUrl);
        }
        return rsdUrl;
    }

    private boolean isHTTPAuthErrorMessage(Exception e) {
        if (e != null && e.getMessage() != null && e.getMessage().contains("401")) {
            mHttpAuthRequired = true;
            return mHttpAuthRequired;
        }
        return false;
    }

    private String getmXmlrpcByUserEnteredPath(String baseUrl) {
        String xmlRpcUrl = null;
        if (!UrlUtils.isValidUrlAndHostNotNull(baseUrl)) {
            AppLog.e(T.NUX, "invalid URL: " + baseUrl);
            mErrorMsgId = R.string.invalid_url_message;
            return null;
        }
        URI uri = URI.create(baseUrl);
        XMLRPCClientInterface client = XMLRPCFactory.instantiate(uri, mHttpUsername, mHttpPassword);
        try {
            client.call("system.listMethods");
            xmlRpcUrl = baseUrl;
            mIsCustomUrl = true;
            return xmlRpcUrl;
        } catch (XMLRPCException e) {
            AppLog.i(T.NUX, "system.listMethods failed on: " + baseUrl);
            if (isHTTPAuthErrorMessage(e)) {
                return null;
            }
        } catch (SSLHandshakeException e) {
            if (!UrlUtils.getDomainFromUrl(baseUrl).endsWith("wordpress.com")) {
                mErroneousSslCertificate = true;
            }
            AppLog.w(T.NUX, "SSLHandshakeException failed. Erroneous SSL certificate detected.");
            return null;
        } catch (SSLPeerUnverifiedException e) {
            if (!UrlUtils.getDomainFromUrl(baseUrl).endsWith("wordpress.com")) {
                mErroneousSslCertificate = true;
            }
            AppLog.w(T.NUX, "SSLPeerUnverifiedException failed. Erroneous SSL certificate detected.");
            return null;
        } catch (IOException e) {
            AppLog.i(T.NUX, "system.listMethods failed on: " + baseUrl);
            if (isHTTPAuthErrorMessage(e)) {
                return null;
            }
        } catch (XmlPullParserException e) {
            AppLog.i(T.NUX, "system.listMethods failed on: " + baseUrl);
            if (isHTTPAuthErrorMessage(e)) {
                return null;
            }
        }

        // Guess the xmlrpc path
        String guessURL = baseUrl;
        if (guessURL.substring(guessURL.length() - 1, guessURL.length()).equals("/")) {
            guessURL = guessURL.substring(0, guessURL.length() - 1);
        }
        guessURL += "/xmlrpc.php";
        uri = URI.create(guessURL);
        client = XMLRPCFactory.instantiate(uri, mHttpUsername, mHttpPassword);
        try {
            client.call("system.listMethods");
            xmlRpcUrl = guessURL;
            return xmlRpcUrl;
        } catch (XMLRPCException e) {
            AppLog.e(T.NUX, "system.listMethods failed on: " + guessURL, e);
        } catch (SSLHandshakeException e) {
            if (!UrlUtils.getDomainFromUrl(baseUrl).endsWith("wordpress.com")) {
                mErroneousSslCertificate = true;
            }
            AppLog.w(T.NUX, "SSLHandshakeException failed. Erroneous SSL certificate detected.");
            return null;
        } catch (SSLPeerUnverifiedException e) {
            if (!UrlUtils.getDomainFromUrl(baseUrl).endsWith("wordpress.com")) {
                mErroneousSslCertificate = true;
            }
            AppLog.w(T.NUX, "SSLPeerUnverifiedException failed. Erroneous SSL certificate detected.");
            return null;
        } catch (IOException e) {
            AppLog.e(T.NUX, "system.listMethods failed on: " + guessURL, e);
        } catch (XmlPullParserException e) {
            AppLog.e(T.NUX, "system.listMethods failed on: " + guessURL, e);
        }

        return null;
    }

    // Attempts to retrieve the xmlrpc url for a self-hosted site, in this order:
    // 1: Try to retrieve it by finding the ?rsd url in the site's header
    // 2: Take whatever URL the user entered to see if that returns a correct response
    // 3: Finally, just guess as to what the xmlrpc url should be
    private String getSelfHostedXmlrpcUrl(String url) {
        String xmlrpcUrl;

        // Convert IDN names to punycode if necessary
        url = UrlUtils.convertUrlToPunycodeIfNeeded(url);

        // Add http to the beginning of the URL if needed
        url = UrlUtils.addUrlSchemeIfNeeded(url, false);

        if (!URLUtil.isValidUrl(url)) {
            mErrorMsgId = R.string.invalid_url_message;
            return null;
        }

        // Attempt to get the XMLRPC URL via RSD
        String rsdUrl;
        try {
            rsdUrl = getRsdUrl(url);
        } catch (SSLHandshakeException e) {
            if (!UrlUtils.getDomainFromUrl(url).endsWith("wordpress.com")) {
                mErroneousSslCertificate = true;
            }
            AppLog.w(T.NUX, "SSLHandshakeException failed. Erroneous SSL certificate detected.");
            return null;
        }

        try {
            if (rsdUrl != null) {
                xmlrpcUrl = ApiHelper.getXMLRPCUrl(rsdUrl);
                if (xmlrpcUrl == null) {
                    xmlrpcUrl = rsdUrl.replace("?rsd", "");
                }
            } else {
                xmlrpcUrl = getmXmlrpcByUserEnteredPath(url);
            }
        } catch (SSLHandshakeException e) {
            if (!UrlUtils.getDomainFromUrl(url).endsWith("wordpress.com")) {
                mErroneousSslCertificate = true;
            }
            AppLog.w(T.NUX, "SSLHandshakeException failed. Erroneous SSL certificate detected.");
            return null;
        }

        return xmlrpcUrl;
    }
}
