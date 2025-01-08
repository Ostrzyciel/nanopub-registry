package com.knowledgepixels.registry.jelly;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.extra.server.ServerInfo;
import org.nanopub.extra.server.ServerIterator;
import org.nanopub.trusty.TrustyNanopubUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * Jelly version of GetNanopub class in nanopub-java.
 * TODO: merge this into nanopub-java?
 *       This could be done by simply configuring the preferred RDF format.
 *       JellyUtils.JELLY_FORMAT object contains the needed info about the content type.
 */
public class JellyGetNanopub {

    /**
     * Adapted from nanopub-java's GetNanopub.get method.
     * TODO: merge this into nanopub-java?
     */
    public static Nanopub get(String uriOrArtifactCode) {
        HttpClient httpClient = NanopubUtils.getHttpClient();
        ServerIterator serverIterator = new ServerIterator();
        String ac = GetNanopub.getArtifactCode(uriOrArtifactCode);
        if (!ac.startsWith("RA")) {
            throw new IllegalArgumentException("Not a trusty URI of type RA");
        } else {
            while(serverIterator.hasNext()) {
                ServerInfo serverInfo = serverIterator.next();

                try {
                    Nanopub np = get(ac, serverInfo.getPublicUrl(), httpClient);
                    if (np != null) {
                        return np;
                    }
                } catch (IOException ex) {
                } catch (RDF4JException ex) {
                } catch (MalformedNanopubException ex) {
                }
            }

            return null;
        }
    }

    /**
     * Adapted from nanopub-java's GetNanopub.get method.
     * TODO: merge this into nanopub-java?
     */
    public static Nanopub get(String artifactCode, String serverUrl, HttpClient httpClient)
            throws IOException, RDF4JException, MalformedNanopubException
    {
        HttpGet get;
        try {
            get = new HttpGet(serverUrl + artifactCode);
        } catch (IllegalArgumentException var11) {
            throw new IOException("invalid URL: " + serverUrl + artifactCode);
        }

        get.setHeader("Accept", "application/x-jelly-rdf");
        InputStream in = null;

        Nanopub nanopub;
        try {
            HttpResponse resp = httpClient.execute(get);
            if (!wasSuccessful(resp)) {
                EntityUtils.consumeQuietly(resp.getEntity());
                throw new IOException(resp.getStatusLine().toString());
            }

            in = resp.getEntity().getContent();
            nanopub = JellyUtils.readFromInputStream(in);
            if (!TrustyNanopubUtils.isValidTrustyNanopub(nanopub)) {
                throw new MalformedNanopubException("Nanopub is not trusty");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (in != null) {
                in.close();
            }
        }

        return nanopub;
    }

    /**
     * Adapted from nanopub-java's GetNanopub.wasSuccessful method
     */
    private static boolean wasSuccessful(HttpResponse resp) {
        int c = resp.getStatusLine().getStatusCode();
        return c >= 200 && c < 300;
    }
}
