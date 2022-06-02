package com.facebook.airlift.http.server;
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closer;
import com.facebook.airlift.event.client.NullEventClient;
import com.facebook.airlift.http.client.HttpClientConfig;
import com.facebook.airlift.http.client.StatusResponseHandler.StatusResponse;
import com.facebook.airlift.http.client.jetty.JettyHttpClient;
import com.facebook.airlift.http.server.HttpServer.ClientCertificate;
import com.facebook.airlift.node.NodeConfig;
import com.facebook.airlift.node.NodeInfo;
import com.facebook.airlift.tracetoken.TraceTokenManager;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Optional;

import static com.google.common.io.Resources.getResource;
import static com.facebook.airlift.http.client.Request.Builder.prepareGet;
import static com.facebook.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.assertEquals;

public class TestJettyMultipleCerts
{
    private static final String SINGLE = "single";

    @Test
    public void test()
            throws Exception
    {
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
                    throws IOException
            {
                response.setStatus(200);
                response.getOutputStream().write((request.getServerName() + " OK").getBytes(UTF_8));
            }
        };

        HttpServerConfig config = new HttpServerConfig()
                .setHttpEnabled(false)
                .setHttpPort(0)
                .setHttpsEnabled(true)
                .setHttpsPort(0)
                .setKeystorePath(getResource("multiple-certs/server.p12").getPath())
                .setKeystorePassword("airlift");

        HashLoginServiceProvider loginServiceProvider = new HashLoginServiceProvider(config);
        NodeInfo nodeInfo = new NodeInfo(new NodeConfig()
                .setEnvironment("test")
                .setNodeInternalAddress("localhost"));

        HttpServerInfo httpServerInfo = new HttpServerInfo(config, nodeInfo);
        HttpServerProvider serverProvider = new HttpServerProvider(
                httpServerInfo,
                nodeInfo,
                config,
                servlet,
                ImmutableMap.of(),
                ImmutableSet.of(new DummyFilter()),
                ImmutableSet.of(),
                ImmutableSet.of(),
                ClientCertificate.NONE,
                new RequestStats(),
                new NullEventClient(),
                Optional.empty());
        serverProvider.setTheAdminServlet(new DummyServlet());
        serverProvider.setLoginService(loginServiceProvider.get());
        serverProvider.setTokenManager(new TraceTokenManager());

        try (Closer closer = Closer.create()) {
            HttpServer server = serverProvider.get();
            closer.register(() -> {
                try {
                    server.stop();
                }
                catch (Exception ignore) {
                }
            });
            server.start();

            JettyHttpClient client = new JettyHttpClient(new HttpClientConfig()
                    .setTrustStorePath(getResource("multiple-certs/server.p12").getPath())
                    .setTrustStorePassword("airlift"));
            closer.register(client);

            tryHost(client, "localhost", httpServerInfo.getHttpsUri().getPort());
            tryHost(client, "127.0.0.1", httpServerInfo.getHttpsUri().getPort());

            // this only work if "single" was manually added to /etc/hosts
            if (doesDomainResolveToLocalhost(SINGLE)) {
                tryHost(client, SINGLE, httpServerInfo.getHttpsUri().getPort());
            }
        }
    }

    private boolean doesDomainResolveToLocalhost(String single)
    {
        try {
            return ImmutableList.copyOf(InetAddress.getAllByName(single)).contains(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        }
        catch (UnknownHostException ignored) {
            return false;
        }
    }

    private void tryHost(JettyHttpClient client, String hostname, int port)
    {
        try {
            StatusResponse statusResponse = client.execute(
                    prepareGet()
                            .setUri(URI.create("https://" + hostname + ":" + port + "/"))
                            .build(),
                    createStatusResponseHandler());
            assertEquals(statusResponse.getStatusCode(), 200);
        }
        catch (Exception e) {
            throw new RuntimeException(hostname + " FAILED", e);
        }
    }
}