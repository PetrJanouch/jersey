package org.glassfish.jersey.jdk.connector;

import java.io.InputStream;

/**
 * Created by petr on 03/05/15.
 */
public abstract class NioInputStream extends InputStream {

    public abstract boolean isReady();
}
