package org.example;

import java.nio.charset.StandardCharsets;

public class Protocol {
    public static final int BUFFER_SIZE = 8192;
    public static final int MAX_FILENAME_LENGTH = 4096;
    public static final long MAX_FILE_SIZE = 1L * 1024 * 1024 * 1024 * 1024;

    public static final int READY_FOR_METADATA = 100;
    public static final int READY_FOR_FILE = 101;
    public static final int SUCCESS = 200;
    public static final int ERROR_INVALID_FILENAME = 400;
    public static final int ERROR_DISK_FULL = 401;
    public static final int ERROR_TRANSFER_FAILED = 402;
    public static final int ERROR_FILE_TOO_LARGE = 403;

    public static final int SPEED_REPORT_INTERVAL_MS = 3000;
    public static final int SOCKET_TIMEOUT_MS = 30000;

    public static final String STRING_ENCODING = StandardCharsets.UTF_8.name();
    public static final String UPLOAD_DIR = "uploads";

}
