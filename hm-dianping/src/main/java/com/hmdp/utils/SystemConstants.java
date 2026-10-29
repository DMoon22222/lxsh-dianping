package com.hmdp.utils;

import java.io.File;

public class SystemConstants {
    private static final String LOCAL_NGINX_IMAGE_DIR =
            "F:/hmdpcode/nginx-1.18.0/nginx-1.18.0/nginx-1.18.0/html/hmdp/imgs/";
    private static final String LEGACY_NGINX_IMAGE_DIR =
            "F:/hmdpcode/nginx-1.18.0/nginx-1.18.0/html/hmdp/imgs/";

    public static final String IMAGE_UPLOAD_DIR = System.getProperty(
            "hmdp.image-upload-dir",
            System.getenv().getOrDefault(
                    "HMDP_IMAGE_UPLOAD_DIR",
                    new File(LOCAL_NGINX_IMAGE_DIR).exists()
                            ? LOCAL_NGINX_IMAGE_DIR
                            : new File(LEGACY_NGINX_IMAGE_DIR).exists()
                                    ? LEGACY_NGINX_IMAGE_DIR
                                    : "uploads/hmdp/imgs/"
            )
    );
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 10;
}
