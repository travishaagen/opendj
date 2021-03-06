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
 * Copyright 2008-2010 Sun Microsystems, Inc.
 */

package org.opends.server.tools.tasks;

import org.opends.server.types.RawAttribute;
import org.opends.server.backends.task.FailedDependencyAction;

import java.util.List;
import java.util.Date;

/**
 * Interface for tools that are capable of scheduling a task remotely
 * through the task backend.
 *
 * @see TaskClient
 */
public interface TaskScheduleInformation {


  /**
   * Adds utility specific attributes to <code>attributes</code> for
   * population of the entry that is added to the task backend.
   *
   * @param attributes that will be added to the task backend
   */
  void addTaskAttributes(List<RawAttribute> attributes);


  /**
   * Gets the objectclass used to represent scheduled instances of this
   * utility in the task backend.
   *
   * @return String representation of this utilities objectclass
   */
  String getTaskObjectclass();


  /**
   * Gets the Class that implements the utility to execute.
   *
   * @return class of the tasks implementation
   */
  Class<?> getTaskClass();


  /**
   * Gets the date at which this task should be scheduled to start.
   *
   * @return date/time at which the task should be scheduled
   */
  Date getStartDateTime();


  /**
   * Gets an arbitrary task id assigned to this task.
   *
   * @return assigned task id if any or <CODE>null</CODE> otherwise.
   */
  String getTaskId();


  /**
   * Gets the date/time pattern for recurring task schedule.
   *
   * @return recurring date/time pattern at which the task
   *         should be scheduled.
   */
  String getRecurringDateTime();


  /**
   * Gets a list of task IDs upon which this task is dependent.
   *
   * @return list of task IDs
   */
  List<String> getDependencyIds();


  /**
   * Gets the action to take should one of the dependent task fail.
   *
   * @return action to take
   */
  FailedDependencyAction getFailedDependencyAction();


  /**
   * Gets a list of email address to which an email will be sent when this
   * task completes.
   *
   * @return list of email addresses
   */
  List<String> getNotifyUponCompletionEmailAddresses();


  /**
   * Gets a list of email address to which an email will be sent if this
   * task encounters an error during execution.
   *
   * @return list of email addresses
   */
  List<String> getNotifyUponErrorEmailAddresses();


}
