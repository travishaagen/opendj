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
 * Copyright 2006-2008 Sun Microsystems, Inc.
 * Portions Copyright 2015 ForgeRock AS.
 */

package org.opends.guitools.uninstaller;

import org.opends.admin.ads.ServerDescriptor;
import org.opends.admin.ads.util.ApplicationTrustManager;
import org.opends.quicksetup.UserData;

import java.util.Set;
import java.util.HashSet;

/**
 * UserData with specific properties for Uninstall.
 */
public class UninstallUserData extends UserData {

  private Set<String> externalDbsToRemove = new HashSet<>();
  private Set<String> externalLogsToRemove = new HashSet<>();
  private boolean removeDatabases;
  private boolean removeLogs;
  private boolean removeLibrariesAndTools;
  private boolean removeBackups;
  private boolean removeLDIFs;
  private boolean removeConfigurationAndSchema;
  private boolean updateRemoteReplication;
  private ApplicationTrustManager trustManager = new ApplicationTrustManager(null);
  private String adminUID;
  private String adminPwd;
  private String localServerUrl;
  private HashSet<ServerDescriptor> remoteServers = new HashSet<>();
  private String replicationServer;
  private String referencedHostName;

  /**
   * Sets the database directories located outside the installation which must
   * be removed.
   * @param dbPaths the directories of the database files.
   */
  public void setExternalDbsToRemove(Set<String> dbPaths)
  {
    externalDbsToRemove.clear();
    externalDbsToRemove.addAll(dbPaths);
  }

  /**
   * Returns the list of databases located outside the installation that must
   * be removed.
   * @return the list of databases located outside the installation that must
   * be removed.
   */
  public Set<String> getExternalDbsToRemove()
  {
    return new HashSet<>(externalDbsToRemove);
  }

  /**
   * Sets the log files located outside the installation which must
   * be removed.
   * @param logFiles the log files.
   */
  public void setExternalLogsToRemove(Set<String> logFiles)
  {
    externalLogsToRemove.clear();
    externalLogsToRemove.addAll(logFiles);
  }

  /**
   * Returns the list of log files located outside the installation that must
   * be removed.
   * @return the list of log files located outside the installation that must
   * be removed.
   */
  public Set<String> getExternalLogsToRemove()
  {
    return new HashSet<>(externalLogsToRemove);
  }

  /**
   * Returns whether the user wants to remove libraries and tools or not.
   * @return <CODE>true</CODE> if the user wants to remove the libraries and
   * tools and <CODE>false</CODE> otherwise.
   */
  public boolean getRemoveLibrariesAndTools()
  {
    return removeLibrariesAndTools;
  }

  /**
   * Sets whether to remove libraries and tools or not.
   * @param removeLibrariesAndTools remove libraries and tools or not.
   */
  public void setRemoveLibrariesAndTools(boolean removeLibrariesAndTools)
  {
    this.removeLibrariesAndTools = removeLibrariesAndTools;
  }

  /**
   * Sets whether to remove databases or not.
   * @param removeDatabases remove databases or not.
   */
  public void setRemoveDatabases(boolean removeDatabases)
  {
    this.removeDatabases = removeDatabases;
  }

  /**
   * Returns whether the user wants to remove databases or not.
   * @return <CODE>true</CODE> if the user wants to remove the databases and
   * <CODE>false</CODE> otherwise.
   */
  public boolean getRemoveDatabases()
  {
    return removeDatabases;
  }

  /**
   * Sets whether to remove backups or not.
   * @param removeBackups remove backups or not.
   */
  public void setRemoveBackups(boolean removeBackups)
  {
    this.removeBackups = removeBackups;
  }

  /**
   * Returns whether the user wants to remove backups or not.
   * @return <CODE>true</CODE> if the user wants to remove the backups and
   * <CODE>false</CODE> otherwise.
   */
  public boolean getRemoveBackups()
  {
    return removeBackups;
  }

  /**
   * Sets whether to remove log files or not.
   * @param removeLogs remove log files or not.
   */
  public void setRemoveLogs(boolean removeLogs)
  {
    this.removeLogs = removeLogs;
  }

  /**
   * Returns whether the user wants to remove logs or not.
   * @return <CODE>true</CODE> if the user wants to remove the log files and
   * <CODE>false</CODE> otherwise.
   */
  public boolean getRemoveLogs()
  {
    return removeLogs;
  }

  /**
   * Sets whether to remove LDIF files or not.
   * @param removeLDIFs remove LDIF files or not.
   */
  public void setRemoveLDIFs(boolean removeLDIFs)
  {
    this.removeLDIFs = removeLDIFs;
  }

  /**
   * Returns whether the user wants to remove LDIF files or not.
   * @return <CODE>true</CODE> if the user wants to remove the LDIF files and
   * <CODE>false</CODE> otherwise.
   */
  public boolean getRemoveLDIFs()
  {
    return removeLDIFs;
  }

  /**
   * Sets whether to remove configuration and schema files or not.
   * @param removeConfigurationAndSchema remove configuration and schema files
   * or not.
   */
  public void setRemoveConfigurationAndSchema(
      boolean removeConfigurationAndSchema)
  {
    this.removeConfigurationAndSchema = removeConfigurationAndSchema;
  }

  /**
   * Returns whether the user wants to remove configuration and schema files or
   * not.
   * @return <CODE>true</CODE> if the user wants to remove the configuration
   * and schema files and <CODE>false</CODE> otherwise.
   */
  public boolean getRemoveConfigurationAndSchema()
  {
    return removeConfigurationAndSchema;
  }

  /**
   * Sets whether to update remote replication configuration or not.
   * @param updateRemoteReplication update remote replication configuration
   * or not.
   */
  public void setUpdateRemoteReplication(boolean updateRemoteReplication)
  {
    this.updateRemoteReplication = updateRemoteReplication;
  }

  /**
   * Returns whether the user wants to update remote replication configuration
   * or not.
   * @return <CODE>true</CODE> if the user wants to update remote replication
   * configuration and <CODE>false</CODE> otherwise.
   */
  public boolean getUpdateRemoteReplication()
  {
    return updateRemoteReplication;
  }

  /**
   * Returns the trust manager that can be used to establish secure connections.
   * @return the trust manager that can be used to establish secure connections.
   */
  public ApplicationTrustManager getTrustManager() {
    return trustManager;
  }

  /**
   * Sets the trust manager that can be used to establish secure connections.
   * @param trustManager the trust manager that can be used to establish secure
   * connections.
   */
  public void setTrustManager(ApplicationTrustManager trustManager) {
    this.trustManager = trustManager;
  }

  /**
   * Returns the administrator password provided by the user.
   * @return the administrator password provided by the user.
   */
  public String getAdminPwd() {
    return adminPwd;
  }

  /**
   * Sets the administrator password provided by the user.
   * @param adminPwd the administrator password provided by the user.
   */
  public void setAdminPwd(String adminPwd) {
    this.adminPwd = adminPwd;
  }

  /**
   * Returns the administrator UID provided by the user.
   * @return the administrator UID provided by the user.
   */
  public String getAdminUID() {
    return adminUID;
  }

  /**
   * Sets the administrator UID provided by the user.
   * @param adminUID the administrator UID provided by the user.
   */
  public void setAdminUID(String adminUID) {
    this.adminUID = adminUID;
  }

  /**
   * Returns the replication server as referenced in other servers.
   * @return the replication server as referenced in other servers.
   */
  public String getReplicationServer() {
    return replicationServer;
  }

  /**
   * Sets the replication server as referenced in other servers.
   * @param replicationServer the replication server as referenced in other
   * servers.
   */
  public void setReplicationServer(String replicationServer) {
    this.replicationServer = replicationServer;
  }

  /**
   * Returns the server host name as referenced in other servers.
   * @return the server host name as referenced in other servers.
   */
  public String getReferencedHostName() {
    return referencedHostName;
  }

  /**
   * Sets the server host name as referenced in other servers.
   * @param referencedHostName server host name as referenced in other
   * servers.
   */
  public void setReferencedHostName(String referencedHostName) {
    this.referencedHostName = referencedHostName;
  }

  /**
   * Returns the LDAP URL that we used to connect to the local server.
   * @return the LDAP URL that we used to connect to the local server.
   */
  public String getLocalServerUrl() {
    return localServerUrl;
  }

  /**
   * Sets the LDAP URL that we used to connect to the local server.
   * @param localServerUrl the LDAP URL that we used to connect to the local
   * server.
   */
  public void setLocalServerUrl(String localServerUrl) {
    this.localServerUrl = localServerUrl;
  }

  /**
   * Returns a Set containing the ServerDescriptors discovered in the
   * TopologyCache.
   * @return a Set containing the ServerDescriptors discovered in the
   * TopologyCache.
   */
  public Set<ServerDescriptor> getRemoteServers()
  {
    return new HashSet<>(remoteServers);
  }

  /**
   * Sets the ServerDescriptors discovered in the TopologyCache.
   * @param remoteServers the Set containing the ServerDescriptors discovered in
   * the TopologyCache.
   */
  public void setRemoteServers(Set<ServerDescriptor> remoteServers)
  {
    this.remoteServers.clear();
    this.remoteServers.addAll(remoteServers);
  }

}
