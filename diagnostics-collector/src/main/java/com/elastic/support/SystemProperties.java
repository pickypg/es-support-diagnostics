package com.elastic.support;

import javax.annotation.PostConstruct;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane;
import java.net.InetAddress;
import java.net.UnknownHostException;


public class SystemProperties {

    public static final String osName = System.getProperty("os.name");

    public static final String pathSeparator = System.getProperty("path.separator");

    public static final String fileSeparator = System.getProperty("file.separator");

    public static final String userDir = System.getProperty("user.dir");

    public static final String userHome = System.getProperty("user.home");

}
