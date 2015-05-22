package org.glassfish.jersey.jdk.connector;

import java.io.OutputStream;

/**
 * Created by petr on 03/05/15.
 */
public abstract class NioOutputStream extends OutputStream {

    public abstract boolean isReady();
}
