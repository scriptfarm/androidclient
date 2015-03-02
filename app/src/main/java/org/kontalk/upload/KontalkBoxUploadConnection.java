/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.upload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Environment;

import org.kontalk.Kontalk;
import org.kontalk.client.ClientHTTPConnection;
import org.kontalk.client.EndpointServer;
import org.kontalk.crypto.Coder;
import org.kontalk.crypto.PersonalKey;
import org.kontalk.provider.UsersProvider;
import org.kontalk.service.ProgressListener;
import org.kontalk.util.Preferences;
import org.kontalk.util.ProgressInputStreamEntity;


/**
 * Upload service implementation for Kontalk Box dropbox service.
 * @author Daniele Ricci
 */
public class KontalkBoxUploadConnection implements UploadConnection {
    private static final String TAG = KontalkBoxUploadConnection.class.getSimpleName();

    /** The authentication token header. */
    private static final String HEADER_NAME_AUTHORIZATION = "Authorization";
    private static final String HEADER_VALUE_AUTHORIZATION = "KontalkToken auth=";

    /** Message flags header. */
    private static final String HEADER_MESSAGE_FLAGS = "X-Message-Flags";

    protected final Context mContext;

    protected HttpRequestBase currentRequest;
    protected HttpClient mConnection;

    private final PrivateKey mPrivateKey;
    private final X509Certificate mCertificate;

    private final String mBaseUrl;

    public KontalkBoxUploadConnection(Context context, String url,
            PrivateKey privateKey, X509Certificate bridgeCert) {
        mContext = context;
        mBaseUrl = url;
        mPrivateKey = privateKey;
        mCertificate = bridgeCert;
    }

    @Override
    public void abort() {
        if (currentRequest != null)
            currentRequest.abort();
    }

    @Override
    public String upload(Uri uri, String mime, boolean encrypt, String to, ProgressListener listener)
            throws IOException {

        HttpResponse response = null;
        InputStream inMessage = null;
        try {
            AssetFileDescriptor stat = mContext.getContentResolver()
                .openAssetFileDescriptor(uri, "r");
            long inLength = stat.getLength();
            stat.close();

            inMessage = mContext.getContentResolver().openInputStream(uri);

            boolean encrypted = false;
            // check if we have to encrypt the message
            if (encrypt) {
                PersonalKey key = ((Kontalk)mContext.getApplicationContext()).getPersonalKey();
                EndpointServer server = Preferences.getEndpointServer(mContext);
                Coder coder = UsersProvider.getEncryptCoder(mContext, server, key, new String[] { to });
                if (coder != null) {
                    // create a temporary file to store encrypted data
                    File temp = File.createTempFile("media", null, mContext.getCacheDir());
                    FileOutputStream out = new FileOutputStream(temp);

                    coder.encryptFile(inMessage, out);
                    // close original file and encrypted file
                    inMessage.close();
                    out.close();

                    // open the encrypted file
                    inMessage = new FileInputStream(temp);
                    inLength = temp.length();
                    encrypted = true;

                    // delete the encrypted file
                    // it will stay until all streams are closed
                    temp.delete();
                }
            }

            // http request!
            currentRequest = prepareMessage(listener,
                mime, inMessage, inLength, encrypted);
            response = execute(currentRequest);
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
                throw new HttpException(response.getStatusLine().getReasonPhrase());

            return EntityUtils.toString(response.getEntity());
        }
        catch (Exception e) {
            throw innerException("upload error", e);
        }
        finally {
            currentRequest = null;
            try {
                response.getEntity().consumeContent();
            }
            catch (Exception e) {
                // ignore
            }
            try {
                inMessage.close();
            }
            catch (Exception e) {
                // ignore
            }
        }
    }

    private IOException innerException(String detail, Throwable cause) {
        IOException ie = new IOException(detail);
        ie.initCause(cause);
        return ie;
    }

    /** A generic endpoint request method for the messaging server. */
    public HttpRequestBase prepare(List<NameValuePair> params,
        String mime, byte[] content, boolean forcePost)
        throws IOException {

        HttpRequestBase req;

        // compose uri
        StringBuilder uri = new StringBuilder(mBaseUrl);
        if (params != null)
            uri.append("?").append(URLEncodedUtils.format(params, "UTF-8"));

        // request type
        if (content != null || forcePost) {
            req = new HttpPost(uri.toString());
            req.setHeader("Content-Type", mime != null ? mime
                : "application/octet-stream");
            if (content != null)
                ((HttpPost) req).setEntity(new ByteArrayEntity(content));
        }
        else
            req = new HttpGet(uri.toString());

        return req;
    }

    /** A message posting method. */
    private HttpRequestBase prepareMessage(ProgressListener listener,
        String mime, InputStream data, long length, boolean encrypted)
            throws IOException {

        HttpPost req = (HttpPost) prepare(null, mime, null, true);
        req.setEntity(new ProgressInputStreamEntity(data, length, this, listener));

        if (encrypted)
            req.addHeader(HEADER_MESSAGE_FLAGS, "encrypted");

        return req;
    }

    /**
     * Executes the given request.
     *
     * @param request
     *            the request
     * @return the response
     * @throws IOException
     */
    private HttpResponse execute(HttpRequestBase request) throws IOException {
        // execute!
        try {
            if (mConnection == null) {
                SchemeRegistry registry = new SchemeRegistry();
                try {
                    registry.register(new Scheme("http",  PlainSocketFactory.getSocketFactory(), 80));

                    boolean acceptAnyCertificate = Preferences.getAcceptAnyCertificate(mContext);
                    registry.register(new Scheme("https", ClientHTTPConnection
                        .setupSSLSocketFactory(mContext, mPrivateKey, mCertificate, acceptAnyCertificate), 443));
                }
                catch (Exception e) {
                    IOException ie = new IOException("unable to create keystore");
                    ie.initCause(e);
                    throw ie;
                }

                HttpParams params = new BasicHttpParams();
                // handle redirects :)
                params.setBooleanParameter(ClientPNames.HANDLE_REDIRECTS, true);
                // HttpClient bug caused by Lighttpd
                params.setBooleanParameter("http.protocol.expect-continue", false);

                // create connection manager
                ClientConnectionManager connMgr = new SingleClientConnManager(params, registry);

                mConnection = new DefaultHttpClient(connMgr, params);
            }
            return mConnection.execute(request);
        }
        catch (ClientProtocolException e) {
            IOException ie = new IOException("client protocol error");
            ie.initCause(e);
            throw ie;
        }

    }

}
