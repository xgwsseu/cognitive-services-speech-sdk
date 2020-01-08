//
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE.md file in the project root for full license information.
//
package com.microsoft.cognitiveservices.speech;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.microsoft.cognitiveservices.speech.util.EventHandlerImpl;
import com.microsoft.cognitiveservices.speech.util.Contracts;
import com.microsoft.cognitiveservices.speech.Recognizer;

/**
 * Connection is a proxy class for managing connection to the speech service of the specified Recognizer.
 * By default, a Recognizer autonomously manages connection to service when needed.
 * The Connection class provides additional methods for users to explicitly open or close a connection and
 * to subscribe to connection status changes.
 * The use of Connection is optional. It is intended for scenarios where fine tuning of application
 * behavior based on connection status is needed. Users can optionally call openConnection() to manually
 * initiate a service connection before starting recognition on the Recognizer associated with this Connection.
 * After starting a recognition, calling openConnection() or closeConnection() might fail. This will not impact
 * the Recognizer or the ongoing recognition. Connection might drop for various reasons, the Recognizer will
 * always try to reinstitute the connection as required to guarantee ongoing operations. In all these cases
 * Connected/Disconnected events will indicate the change of the connection status.
 * Note: close() must be called in order to relinquish underlying resources held by the object.
 * Added in version 1.2.0.
 */
public final class Connection implements Closeable
{
    private static ExecutorService s_executorService;

    static {
        s_executorService = Executors.newCachedThreadPool();
    }

    /**
     * Gets the Connection instance from the specified recognizer.
     * @param recognizer The recognizer associated with the connection.
     * @return The Connection instance of the recognizer.
     */
    public static Connection fromRecognizer(Recognizer recognizer)
    {
        com.microsoft.cognitiveservices.speech.internal.Connection connectionImpl = com.microsoft.cognitiveservices.speech.internal.Connection.FromRecognizer(recognizer.getRecognizerImpl());
        return new Connection(connectionImpl);
    }

    /**
     * Starts to set up connection to the service.
     * Users can optionally call openConnection() to manually set up a connection in advance before starting recognition on the
     * Recognizer associated with this Connection. After starting recognition, calling OpenConnection() might fail, depending on
     * the process state of the Recognizer. But the failure does not affect the state of the associated Recognizer.
     * Note: On return, the connection might not be ready yet. Please subscribe to the Connected event to
     * be notfied when the connection is established.
     * @param forContinuousRecognition indicates whether the connection is used for continuous recognition or single-shot recognition.
     */
    public void openConnection(boolean forContinuousRecognition)
    {
        connectionImpl.Open(forContinuousRecognition);
    }

    /**
     * Closes the connection the service.
     * Users can optionally call closeConnection() to manually shutdown the connection of the associated Recognizer. The call
     * might fail, depending on the process state of the Recognizer. But the failure does not affect the state of the
     * associated Recognizer.
     */
    public void closeConnection()
    {
        connectionImpl.Close();
    }

    /**
     * Sends a message to service.
     * Added in version 1.7.0
     * @param path The message path.
     * @param payload The message payload.
     * @return a future representing the asynchronous operation that sends the message.
     */
    public Future<Void> sendMessageAsync(String path, String payload) {
        final Connection thisConnection = this;
        final String finalPath = path;
        final String finalPayload = payload;

        return s_executorService.submit(new java.util.concurrent.Callable<Void>() {

            public Void call() {
                Runnable runnable = new Runnable() { public void run() { connectionImpl.SendMessageAsync(finalPath, finalPayload); }};
                thisConnection.doAsyncConnectionAction(runnable);
                return null;
        }});
    }

    /**
     * Appends a parameter in a message to service.
     * Added in version 1.7.0
     * @param path The message path.
     * @param propertyName The property name that you want to set.
     * @param propertyValue The value of the property that you want to set.
     */
    public void setMessageProperty(String path, String propertyName, String propertyValue) {
        connectionImpl.SetMessageProperty(path, propertyName, propertyValue);
    }

    private void doAsyncConnectionAction(Runnable connectionImplAction) {
        synchronized (connectionLock) {
            activeAsyncConnectionCounter++;
        }
        if (disposed) {
            throw new IllegalStateException(this.getClass().getName());
        }
        try {
            connectionImplAction.run();
        } finally {
            synchronized (connectionLock) {
                activeAsyncConnectionCounter--;
            }
        }
    }

    private AtomicInteger eventCounter = new AtomicInteger(0);

    /**
     * The Connected event to indicate that the recognizer is connected to service.
     * In order to receive the connected event after subscribing to it, the Connection object itself needs to be alive.
     * If the Connection object owning this event is out of its life time, all subscribed events won't be delivered.
     */
    public final EventHandlerImpl<ConnectionEventArgs> connected = new EventHandlerImpl<ConnectionEventArgs>(eventCounter);

    /**
     * The Diconnected event to indicate that the recognizer is disconnected from service.
     * In order to receive the disconnected event after subscribing to it, the Connection object itself needs to be alive.
     * If the Connection object owning this event is out of its life time, all subscribed events won't be delivered.
     */
    public final EventHandlerImpl<ConnectionEventArgs> disconnected = new EventHandlerImpl<ConnectionEventArgs>(eventCounter);

    /**
     * Dispose of associated resources.
     * close() must be called to relinquish underlying resources correctly.
     */
    @Override
    public void close() {
        synchronized (connectionLock) {
            if (activeAsyncConnectionCounter != 0) {
              throw new IllegalStateException("Cannot dispose a connection while async method is running. Await async method to avoid unexpected disposals.");
            }
            dispose(true);
        }
    }

    /*! \cond PROTECTED */

    private Integer backgroundAttempts = 0;

    /**
     * This method performs cleanup of resources.
     * The Boolean parameter disposing indicates whether the method is called from Dispose (if disposing is true) or from the finalizer (if disposing is false).
     * Derived classes should override this method to dispose resource if needed.
     * @param disposing Flag to request disposal.
     */
    protected void dispose(final boolean disposing) {
        if (disposed) {
            return;
        }

        if (disposing) {
            if(this.eventCounter.get() != 0 && backgroundAttempts <= 50 ) {
                // There is an event callback in progress, closing while in an event call results in SWIG problems, so 
                // spin a thread to try again in 500ms and return.
                Thread t = new Thread(
                    new Runnable(){
                        @Override
                        public void run() {
                            try{
                                Thread.sleep(500 * ++backgroundAttempts);
                                dispose(disposing);
                            } catch (Exception e){}
                        }
                    });
                t.start();
            }
            else {
                // disconnect
                if (this.connected.isUpdateNotificationOnConnectedFired())
                    connectionImpl.getConnected().DisconnectAll();
                if (this.disconnected.isUpdateNotificationOnConnectedFired())
                    connectionImpl.getDisconnected().DisconnectAll();

                connectedHandler.delete();
                disconnectedHandler.delete();
                connectionImpl.delete();
                _connectionObjects.remove(this);
                disposed = true;
            }
        }
    }

    /*! \endcond */

    /**
     * This is used to keep any instance of this class alive that is subscribed to downstream events.
     */
    static java.util.Set<Connection> _connectionObjects = java.util.Collections.synchronizedSet(new java.util.HashSet<Connection>());

    private com.microsoft.cognitiveservices.speech.internal.Connection connectionImpl;
    private ConnectionEventHandlerImpl connectedHandler;
    private ConnectionEventHandlerImpl disconnectedHandler;
    private boolean disposed = false;
    private final Object connectionLock = new Object();
    private int activeAsyncConnectionCounter = 0;

    private Connection(com.microsoft.cognitiveservices.speech.internal.Connection connectionImpl)
    {
        Contracts.throwIfNull(connectionImpl, "RecognizerInternalImplementation");
        this.connectionImpl = connectionImpl;
        initialize();
    }

    private void initialize()
    {
        final Connection _this = this;

        this.connectedHandler = new ConnectionEventHandlerImpl(this, true);
        this.connected.updateNotificationOnConnected(new Runnable(){
            @Override
            public void run() {
                _connectionObjects.add(_this);
                connectionImpl.getConnected().AddEventListener(connectedHandler);
            }
        });

        this.disconnectedHandler = new ConnectionEventHandlerImpl(this,false);
        this.disconnected.updateNotificationOnConnected(new Runnable(){
            @Override
            public void run() {
                _connectionObjects.add(_this);
                connectionImpl.getDisconnected().AddEventListener(disconnectedHandler);
            }
        });
    }

    /**
     * Define a private class which raises an event when a corresponding callback is invoked from the native layer.
     */
    private class ConnectionEventHandlerImpl extends com.microsoft.cognitiveservices.speech.internal.ConnectionEventListener {

        public ConnectionEventHandlerImpl(Connection connection, Boolean isConnectedEvent) {
            Contracts.throwIfNull(connection, "connection");

            this.connection = connection;
            this.isConnectedEvent= isConnectedEvent;
        }

        @Override
        public void Execute(com.microsoft.cognitiveservices.speech.internal.ConnectionEventArgs eventArgs) {
            Contracts.throwIfNull(eventArgs, "eventArgs");

            if (connection.disposed) {
                return;
            }

            ConnectionEventArgs arg = new ConnectionEventArgs(eventArgs);
            EventHandlerImpl<ConnectionEventArgs>  handler = this.isConnectedEvent ?
                    this.connection.connected : this.connection.disconnected;

            if (handler != null) {
                handler.fireEvent(this.connection, arg);
            }
        }

        private Connection connection;
        private Boolean isConnectedEvent;
    }
}


