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

package org.apache.ftpserver.impl;

import org.apache.ftpserver.ftplet.FtpRequest;

/**
 * <strong>Internal class, do not use directly.</strong>
 *
 * FTP request object.
 *
 * <p><b>ftpshare 改动（相对上游 1.1.1）：命令参数支持 RFC 3659 quoted-string。</b></p>
 *
 * <p>上游 {@code parseArg} 把第一个空格后的全部内容原样作为参数，不做任何引号处理。
 * 但不少客户端对<b>含空格的路径</b>按 RFC 3659 §2.3 的 quoted-string 惯例发送：
 * {@code CWD "my folder"}、{@code RETR "my folder/inside.txt"}（实测 ES 文件管理器、
 * 部分 Android 文件管理器与 Windows 资源管理器 FTP 扩展均如此）。上游会把引号当
 * 文件名的一部分，这类路径一律 550/450——目录明明存在却"访问不了"。</p>
 *
 * <p>修法：参数若<b>整体</b>被一对引号包裹，剥掉外层引号；引号内的 {@code ""}
 * 按 RFC 3659 规则还原为一个 {@code "}。非配对、不带引号的参数逐字不变，
 * FileZilla / curl / Windows ftp.exe 等不加引号的客户端行为完全不受影响。
 * 这是命令层单点修复，CWD/LIST/RETR/STOR/DELE/MD5 等所有命令统一受益。</p>
 */
public class DefaultFtpRequest implements FtpRequest {

    private final String line;

    private final String command;

    private final String argument;

    /**
     * timestamp when this request was received
     */
    private final long receivedTime;

    /**
     * Default constructor.
     */
    public DefaultFtpRequest(final String requestLine) {
        // Assuming we create the request as soon as we receive the command from
        // the client, set the received time to current time. If we do a whole
        // bunch of things after we receive the command from the client and
        // before constructing this FtpRequest object, then this method is not
        // going to be accurate and need to look for an alternative solution.
        this.receivedTime = System.currentTimeMillis();
        line = requestLine == null ? "" : stripLineEnding(requestLine);
        int spInd = firstSeparator(line);
        command = parseCmd(line, spInd);
        argument = parseArg(line, spInd);
    }

    private static String stripLineEnding(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\r' || value.charAt(end - 1) == '\n')) end--;
        return value.substring(0, end);
    }

    private static int firstSeparator(String value) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == ' ' || ch == '\t') return index;
        }
        return -1;
    }

    /**
     * Parse the ftp command line.
     */
    private String parseCmd(final String lineToParse, int spInd) {
        String cmd = null;
        if (spInd != -1) {
            cmd = line.substring(0, spInd).toUpperCase(java.util.Locale.ROOT);
        } else {
            cmd = line.toUpperCase(java.util.Locale.ROOT);
        }
        if ((cmd.length() > 0) && (cmd.charAt(0) == 'X')) {
            cmd = cmd.substring(1);
        }
        return cmd;
    }

    private String parseArg(final String lineToParse, int spInd) {
        String arg = null;
        if (spInd != -1) {
            arg = line.substring(spInd + 1);
            if ("USER".equals(command) && arg.trim().isEmpty()) {
                arg = null;
            } else if (isPathArgument(command)) {
                arg = unquote(arg);
            }
        }
        return arg;
    }

    /** Quoted-string compatibility is only for pathname commands; USER/PASS are opaque. */
    private static boolean isPathArgument(String command) {
        return "APPE".equals(command) || "CWD".equals(command) || "DELE".equals(command)
                || "LIST".equals(command) || "MDTM".equals(command) || "MFMT".equals(command)
                || "MLSD".equals(command) || "MKD".equals(command) || "NLST".equals(command)
                || "RNFR".equals(command) || "RNTO".equals(command) || "RETR".equals(command)
                || "RMD".equals(command) || "SIZE".equals(command) || "STOR".equals(command)
                || "STOU".equals(command);
    }

    /**
     * ftpshare: strip a wrapping quoted-string per RFC 3659 §2.3.
     *
     * Only strips when the argument is entirely wrapped in one pair of quotes;
     * unquoted arguments pass through untouched, so clients that do not quote
     * (the overwhelming majority) behave exactly as upstream.
     */
    private static String unquote(final String arg) {
        int end = arg.length() - 1;
        while (end > 0 && (arg.charAt(end) == ' ' || arg.charAt(end) == '\t')) end--;
        if (end < 1 || arg.charAt(0) != '"' || arg.charAt(end) != '"') {
            return arg;
        }
        String inner = arg.substring(1, end);
        // RFC 3659 §2.3: a literal quote inside a quoted-string is doubled ("").
        // Fast path: no doubled quote, nothing to unescape.
        if (inner.indexOf('"') == -1) {
            return inner;
        }
        return inner.replace("\"\"", "\"");
    }

    /**
     * Get the ftp command.
     */
    public String getCommand() {
        return command;
    }

    /**
     * Get ftp input argument.
     */
    public String getArgument() {
        return argument;
    }

    /**
     * Get the ftp request line.
     */
    public String getRequestLine() {
        return line;
    }

    /**
     * Has argument.
     */
    public boolean hasArgument() {
        return getArgument() != null;
    }

    public long getReceivedTime() {
        return receivedTime;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return getRequestLine();
    }
}
