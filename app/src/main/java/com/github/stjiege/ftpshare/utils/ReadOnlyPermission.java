package com.github.stjiege.ftpshare.utils;

import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.AuthorizationRequest;
import org.apache.ftpserver.usermanager.impl.WriteRequest;

/** Allows read/list authorization requests but never grants a write request. */
public final class ReadOnlyPermission implements Authority {
    @Override
    public AuthorizationRequest authorize(AuthorizationRequest request) {
        return request instanceof WriteRequest ? null : request;
    }

    @Override
    public boolean canAuthorize(AuthorizationRequest request) {
        return !(request instanceof WriteRequest);
    }
}

