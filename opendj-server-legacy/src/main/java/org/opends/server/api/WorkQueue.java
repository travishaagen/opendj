/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2006-2010 Sun Microsystems, Inc.
 * Portions Copyright 2013-2016 ForgeRock AS.
 */
package org.opends.server.api;

import static org.opends.messages.CoreMessages.*;

import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.server.config.server.WorkQueueCfg;
import org.forgerock.opendj.config.server.ConfigException;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.InitializationException;
import org.opends.server.types.Operation;
import org.opends.server.util.Platform;

/**
 * This class defines the structure and methods that must be
 * implemented by a Directory Server work queue.  The work queue is
 * the component of the server that accepts requests from connection
 * handlers and ensures that they are properly processed.  The manner
 * in which the work queue is able to accomplish this may vary between
 * implementations, but in general it is assumed that one or more
 * worker threads will be associated with the queue and may be used to
 * process requests in parallel.
 *
 * @param  <T>  The type of configuration handled by this work queue.
 */
@org.opends.server.types.PublicAPI(
     stability=org.opends.server.types.StabilityLevel.VOLATILE,
     mayInstantiate=false,
     mayExtend=true,
     mayInvoke=true)
public abstract class WorkQueue<T extends WorkQueueCfg>
{

  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();

  /**
   * Initializes this work queue based on the information in the
   * provided configuration entry.
   *
   * @param  configuration  The configuration to use to initialize
   *                        the work queue.
   *
   * @throws  ConfigException  If the provided configuration entry
   *                           does not have a valid work queue
   *                           configuration.
   *
   * @throws  InitializationException  If a problem occurs during
   *                                   initialization that is not
   *                                   related to the server
   *                                   configuration.
   */
  public abstract void initializeWorkQueue(T configuration)
         throws ConfigException, InitializationException;



  /**
   * Performs any necessary finalization for this work queue,
   * including ensuring that all active operations are interrupted or
   * will be allowed to complete, and that all pending operations will
   * be cancelled.
   *
   * @param  reason  The human-readable reason that the work queue is
   *                 being shut down.
   */
  public abstract void finalizeWorkQueue(LocalizableMessage reason);



  /**
   * Submits an operation to be processed in the server.
   *
   * @param  operation  The operation to be processed.
   *
   * @throws  DirectoryException  If the provided operation is not
   *                              accepted for some reason (e.g., if
   *                              the server is shutting down or
   *                              already has too many pending
   *                              requests in the queue).
   */
  public abstract void submitOperation(Operation operation)
         throws DirectoryException;



  /**
   * Tries to submit an operation to be processed in the server, without
   * blocking.
   *
   * @param operation
   *          The operation to be processed.
   * @return true if the operation could be submitted to the queue, false if the
   *         queue was full
   * @throws DirectoryException
   *           If the provided operation is not accepted for some reason (e.g.,
   *           if the server is shutting down).
   */
  public abstract boolean trySubmitOperation(Operation operation)
      throws DirectoryException;


  /**
   * Indicates whether the work queue is currently processing any
   * requests.  Note that this is a point-in-time determination, and
   * if any component of the server wishes to depend on a quiescent
   * state then it should use some external mechanism to ensure that
   * no other requests are submitted to the queue.
   *
   * @return  {@code true} if the work queue is currently idle, or
   *          {@code false} if it is being used to process one or more
   *          operations.
   */
  public abstract boolean isIdle();


  /**
   * Return the maximum number of worker threads that can be used by this
   * WorkQueue (The WorkQueue could have a thread pool which adjusts its size).
   *
   * @return the maximum number of worker threads that can be used by this
   *         WorkQueue
   */
  public abstract int getNumWorkerThreads();


  /**
   * Computes the number of worker threads to use by the working queue based on
   * the configured number.
   *
   * @param configuredNumWorkerThreads
   *          the configured number of worker threads to use
   * @return the number of worker threads to use
   */
  protected int computeNumWorkerThreads(Integer configuredNumWorkerThreads)
  {
    if (configuredNumWorkerThreads != null)
    {
      return configuredNumWorkerThreads;
    }
    else
    {
      // Automatically choose based on the number of processors.
      int value = Platform.computeNumberOfThreads(16, 2.0f);
      logger.debug(INFO_ERGONOMIC_SIZING_OF_WORKER_THREAD_POOL, value);
      return value;
    }
  }

  /**
   * Waits for the work queue to become idle before returning.  Note
   * that this is a point-in-time determination, and if any component
   * of the server wishes to depend on a quiescent state then it
   * should use some external mechanism to ensure that no other
   * requests are submitted to the queue.
   *
   * @param  timeLimit  The maximum length of time in milliseconds
   *                    that this method should wait for the queue to
   *                    become idle before giving up.  A time limit
   *                    that is less than or equal to zero indicates
   *                    that there should not be a time limit.
   *
   * @return  {@code true} if the work queue is idle at the time that
   *          this method returns, or {@code false} if the wait time
   *          limit was reached before the server became idle.
   */
  public boolean waitUntilIdle(long timeLimit)
  {
    long stopWaitingTime;
    if (timeLimit <= 0)
    {
      stopWaitingTime = Long.MAX_VALUE;
    }
    else
    {
      stopWaitingTime = System.currentTimeMillis() + timeLimit;
    }

    while (System.currentTimeMillis() < stopWaitingTime)
    {
      if (isIdle())
      {
        return true;
      }

      try
      {
        Thread.sleep(1);
      } catch (InterruptedException ie) {}
    }

    return false;
  }
}

