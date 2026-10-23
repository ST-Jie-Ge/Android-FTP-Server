/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ftpserver.command.impl;

import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.FtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import org.apache.ftpserver.impl.LocalizedFtpReply;
import org.apache.ftpserver.impl.ServerDataConnectionFactory;
import org.apache.ftpserver.util.SocketAddressEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <strong>Internal class, do not use directly.</strong>
 *
 * <code>PASV</code><br>
 *
 * Opens a passive data connection and reports its address in a 227 reply.
 *
 * <p>Upstream bug fixed here: the address was encoded verbatim through
 * {@link SocketAddressEncoder}, which only understands dotted IPv4. On a
 * dual-stack listen socket an IPv4-mapped address ({@code ::ffff:a.b.c.d})
 * was emitted as-is, producing an invalid 227 that clients could not parse.
 * We unwrap IPv4-mapped addresses to plain IPv4 before encoding; a genuine
 * IPv6 address (which the RFC 959 227 syntax cannot express) falls back to a
 * 425, since clients must use EPSV for those.</p>
 */
public class PASV extends AbstractCommand {

    private final Logger LOG = LoggerFactory.getLogger(PASV.class);

    /**
     * Execute command.
     */
    public void execute(final FtpIoSession session,
            final FtpServerContext context, final FtpRequest request)
            throws IOException {

        // reset state variables
        session.resetState();

        ServerDataConnectionFactory dataCfg = null;
        boolean passiveEndpointAnnounced = false;
        try {
            dataCfg = SessionDataContext.get(session);
            String externalAddress = session.getListener()
                    .getDataConnectionConfiguration().getPassiveExernalAddress();

            InetSocketAddress dataSoc = dataCfg.initPassiveDataConnection();

            InetAddress address;
            if (externalAddress != null) {
                address = InetAddress.getByName(externalAddress);
            } else {
                address = dataSoc.getAddress();
            }

            address = unwrapIpv4Mapped(address);
            if (address.getAddress().length == 16) {
                // RFC 959's 227 syntax cannot express a 16-byte IPv6 address;
                // EPSV (229) must be used for those.
                LOG.warn("PASV cannot express IPv6 address {}, client must use EPSV",
                        address.getHostAddress());
                session.write(LocalizedFtpReply.translate(session, request, context,
                        FtpReply.REPLY_425_CANT_OPEN_DATA_CONNECTION, "PASV", null));
                return;
            }

            dataSoc = new InetSocketAddress(address, dataSoc.getPort());
            String encodedAddress = SocketAddressEncoder.encode(dataSoc);
            session.write(LocalizedFtpReply.translate(session, request, context,
                    FtpReply.REPLY_227_ENTERING_PASSIVE_MODE, "PASV", encodedAddress));
            passiveEndpointAnnounced = true;
        } catch (Exception e) {
            LOG.warn("Failed to open passive data connection", e);
            session.write(LocalizedFtpReply.translate(session, request, context,
                    FtpReply.REPLY_425_CANT_OPEN_DATA_CONNECTION, "PASV", null));
        } finally {
            // initPassiveDataConnection() reserves a listener/port before address encoding.
            // If PASV cannot produce a usable 227 (for example on a native IPv6 address),
            // release that reservation now instead of waiting for a retry or disconnect.
            if (!passiveEndpointAnnounced && dataCfg != null) {
                try {
                    dataCfg.closeDataConnection();
                } catch (RuntimeException cleanupError) {
                    LOG.warn("Failed to release passive data connection after PASV failure", cleanupError);
                }
            }
        }
    }

    /**
     * Unwrap an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) back to its
     * plain IPv4 form so it can be encoded with the IPv4-only 227 syntax.
     * Any other address (including a genuine 16-byte IPv6) is returned unchanged.
     */
    private static InetAddress unwrapIpv4Mapped(InetAddress address)
            throws IOException {
        byte[] raw = address.getAddress();
        if (raw.length != 16) {
            return address;
        }
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return address;
            }
        }
        if (raw[10] != (byte) 0xff || raw[11] != (byte) 0xff) {
            return address;
        }
        return InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16));
    }
}
