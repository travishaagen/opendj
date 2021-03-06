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
 * Copyright 2006-2009 Sun Microsystems, Inc.
 * Portions Copyright 2012-2016 ForgeRock AS.
 */
package org.opends.server.tools;

import static com.forgerock.opendj.cli.ArgumentConstants.*;

import static org.opends.messages.ToolMessages.*;
import static org.opends.server.protocols.ldap.LDAPConstants.*;
import static org.opends.server.util.ServerConstants.*;
import static org.opends.server.util.StaticUtils.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.PrivilegedExceptionAction;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginContext;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.i18n.LocalizableMessageDescriptor.Arg0;
import org.forgerock.i18n.LocalizableMessageDescriptor.Arg1;
import org.forgerock.i18n.LocalizableMessageDescriptor.Arg2;
import org.forgerock.opendj.ldap.ByteSequence;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DecodeException;
import org.opends.server.protocols.ldap.BindRequestProtocolOp;
import org.opends.server.protocols.ldap.BindResponseProtocolOp;
import org.opends.server.protocols.ldap.ExtendedRequestProtocolOp;
import org.opends.server.protocols.ldap.ExtendedResponseProtocolOp;
import org.opends.server.protocols.ldap.LDAPMessage;
import org.opends.server.types.Control;
import org.opends.server.types.LDAPException;
import org.opends.server.util.Base64;

import com.forgerock.opendj.cli.ClientException;
import com.forgerock.opendj.cli.ConsoleApplication;
import com.forgerock.opendj.cli.ReturnCode;

/**
 * This class provides a generic interface that LDAP clients can use to perform
 * various kinds of authentication to the Directory Server.  This handles both
 * simple authentication as well as several SASL mechanisms including:
 * <UL>
 *   <LI>ANONYMOUS</LI>
 *   <LI>CRAM-MD5</LI>
 *   <LI>DIGEST-MD5</LI>
 *   <LI>EXTERNAL</LI>
 *   <LI>GSSAPI</LI>
 *   <LI>PLAIN</LI>
 * </UL>
 * <BR><BR>
 * Note that this implementation is not thread safe, so if the same
 * <CODE>AuthenticationHandler</CODE> object is to be used concurrently by
 * multiple threads, it must be externally synchronized.
 */
public class LDAPAuthenticationHandler
       implements PrivilegedExceptionAction<Object>, CallbackHandler
{
  /** The LDAP reader that will be used to read data from the server. */
  private final LDAPReader reader;
  /** The LDAP writer that will be used to send data to the server. */
  private final LDAPWriter writer;

  /** The atomic integer that will be used to obtain message IDs for request messages. */
  private final AtomicInteger nextMessageID;

  /** An array filled with the inner pad byte. */
  private byte[] iPad;
  /** An array filled with the outer pad byte. */
  private byte[] oPad;

  /** The message digest that will be used to create MD5 hashes. */
  private MessageDigest md5Digest;
  /** The secure random number generator for use by this authentication handler. */
  private SecureRandom secureRandom;

  /** The bind DN for GSSAPI authentication. */
  private ByteSequence gssapiBindDN;
  /** The authentication ID for GSSAPI authentication. */
  private String gssapiAuthID;
  /** The authorization ID for GSSAPI authentication. */
  private String gssapiAuthzID;
  /** The authentication password for GSSAPI authentication. */
  private char[] gssapiAuthPW;
  /** The quality of protection for GSSAPI authentication. */
  private String gssapiQoP;

  /** The host name used to connect to the remote system. */
  private final String hostName;

  /** The SASL mechanism that will be used for callback authentication. */
  private String saslMechanism;



  /**
   * Creates a new instance of this authentication handler.  All initialization
   * will be done lazily to avoid unnecessary performance hits, particularly
   * for cases in which simple authentication will be used as it does not
   * require any particularly expensive processing.
   *
   * @param  reader         The LDAP reader that will be used to read data from
   *                        the server.
   * @param  writer         The LDAP writer that will be used to send data to
   *                        the server.
   * @param  hostName       The host name used to connect to the remote system
   *                        (fully-qualified if possible).
   * @param  nextMessageID  The atomic integer that will be used to obtain
   *                        message IDs for request messages.
   */
  public LDAPAuthenticationHandler(LDAPReader reader, LDAPWriter writer,
                                   String hostName, AtomicInteger nextMessageID)
  {
    this.reader = reader;
    this.writer = writer;
    this.hostName      = hostName;
    this.nextMessageID = nextMessageID;

    md5Digest    = null;
    secureRandom = null;
    iPad         = null;
    oPad         = null;
  }



  /**
   * Retrieves a list of the SASL mechanisms that are supported by this client
   * library.
   *
   * @return  A list of the SASL mechanisms that are supported by this client
   *          library.
   */
  public static String[] getSupportedSASLMechanisms()
  {
    return new String[]
    {
      SASL_MECHANISM_ANONYMOUS,
      SASL_MECHANISM_CRAM_MD5,
      SASL_MECHANISM_DIGEST_MD5,
      SASL_MECHANISM_EXTERNAL,
      SASL_MECHANISM_GSSAPI,
      SASL_MECHANISM_PLAIN
    };
  }



  /**
   * Retrieves a list of the SASL properties that may be provided for the
   * specified SASL mechanism, mapped from the property names to their
   * corresponding descriptions.
   *
   * @param  mechanism  The name of the SASL mechanism for which to obtain the
   *                    list of supported properties.
   *
   * @return  A list of the SASL properties that may be provided for the
   *          specified SASL mechanism, mapped from the property names to their
   *          corresponding descriptions.
   */
  public static Map<String, LocalizableMessage> getSASLProperties(String mechanism)
  {
    switch (toUpperCase(mechanism))
    {
    case SASL_MECHANISM_ANONYMOUS:
      return getSASLAnonymousProperties();
    case SASL_MECHANISM_CRAM_MD5:
      return getSASLCRAMMD5Properties();
    case SASL_MECHANISM_DIGEST_MD5:
      return getSASLDigestMD5Properties();
    case SASL_MECHANISM_EXTERNAL:
      return getSASLExternalProperties();
    case SASL_MECHANISM_GSSAPI:
      return getSASLGSSAPIProperties();
    case SASL_MECHANISM_PLAIN:
      return getSASLPlainProperties();
    default:
      // This is an unsupported mechanism.
      return null;
    }
  }



  /**
   * Processes a bind using simple authentication with the provided information.
   * If the bind fails, then an exception will be thrown with information about
   * the reason for the failure.  If the bind is successful but there may be
   * some special information that the client should be given, then it will be
   * returned as a String.
   *
   * @param  ldapVersion       The LDAP protocol version to use for the bind
   *                           request.
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if it is to be an anonymous
   *                           bind.
   * @param  bindPassword      The password to use to bind to the Directory
   *                           Server, or <CODE>null</CODE> if it is to be an
   *                           anonymous bind.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  public String doSimpleBind(int ldapVersion, ByteSequence bindDN,
                             ByteSequence bindPassword,
                             List<Control> requestControls,
                             List<Control> responseControls)
         throws ClientException, LDAPException
  {
    //Password is empty, set it to ByteString.empty.
    if (bindPassword == null)
    {
        bindPassword = ByteString.empty();
    }

    // Make sure that critical elements aren't null.
    if (bindDN == null)
    {
      bindDN = ByteString.empty();
    }

    sendSimpleBindRequest(ldapVersion, bindDN, bindPassword, requestControls);

    LDAPMessage responseMessage = readBindResponse(ERR_LDAPAUTH_CANNOT_READ_BIND_RESPONSE);
    responseControls.addAll(responseMessage.getControls());
    checkConnected(responseMessage);
    return checkSuccessfulSimpleBind(responseMessage);
  }

  private void sendSimpleBindRequest(int ldapVersion, ByteSequence bindDN, ByteSequence bindPassword,
      List<Control> requestControls) throws ClientException
  {
    BindRequestProtocolOp bindRequest =
        new BindRequestProtocolOp(bindDN.toByteString(), ldapVersion, bindPassword.toByteString());
    LDAPMessage bindRequestMessage = new LDAPMessage(nextMessageID.getAndIncrement(), bindRequest, requestControls);

    try
    {
      writer.writeMessage(bindRequestMessage);
    }
    catch (IOException ioe)
    {
      LocalizableMessage message = ERR_LDAPAUTH_CANNOT_SEND_SIMPLE_BIND.get(getExceptionMessage(ioe));
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message, ioe);
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_CANNOT_SEND_SIMPLE_BIND.get(getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_ENCODING_ERROR, message, e);
    }
  }

  private BindResponseProtocolOp checkSuccessfulBind(LDAPMessage responseMessage, String saslMechanism)
      throws LDAPException
  {
    BindResponseProtocolOp bindResponse = responseMessage.getBindResponseProtocolOp();
    int resultCode = bindResponse.getResultCode();
    if (resultCode != ReturnCode.SUCCESS.get())
    {
      // FIXME -- Add support for referrals.
      LocalizableMessage message = ERR_LDAPAUTH_SASL_BIND_FAILED.get(saslMechanism);
      throw new LDAPException(resultCode, bindResponse.getErrorMessage(), message, bindResponse.getMatchedDN(), null);
    }
    // FIXME -- Need to look for things like password expiration warning, reset notice, etc.
    return bindResponse;
  }

  private String checkSuccessfulSimpleBind(LDAPMessage responseMessage) throws LDAPException
  {
    BindResponseProtocolOp bindResponse = responseMessage.getBindResponseProtocolOp();
    int resultCode = bindResponse.getResultCode();
    if (resultCode != ReturnCode.SUCCESS.get())
    {
      // FIXME -- Add support for referrals.
      LocalizableMessage message = ERR_LDAPAUTH_SIMPLE_BIND_FAILED.get();
      throw new LDAPException(resultCode, bindResponse.getErrorMessage(), message, bindResponse.getMatchedDN(), null);
    }
    // FIXME -- Need to look for things like password expiration warning, reset notice, etc.
    return null;
  }

  /**
   * Processes a SASL bind using the provided information.  If the bind fails,
   * then an exception will be thrown with information about the reason for the
   * failure.  If the bind is successful but there may be some special
   * information that the client should be given, then it will be returned as a
   * String.
   *
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if the authentication identity
   *                           is to be set through some other means.
   * @param  bindPassword      The password to use to bind to the Directory
   *                           Server, or <CODE>null</CODE> if this is not a
   *                           password-based SASL mechanism.
   * @param  mechanism         The name of the SASL mechanism to use to
   *                           authenticate to the Directory Server.
   * @param  saslProperties    A set of additional properties that may be needed
   *                           to process the SASL bind.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  public String doSASLBind(ByteSequence bindDN, ByteSequence bindPassword,
                           String mechanism,
                           Map<String,List<String>> saslProperties,
                           List<Control> requestControls,
                           List<Control> responseControls)
         throws ClientException, LDAPException
  {
    // Make sure that critical elements aren't null.
    if (bindDN == null)
    {
      bindDN = ByteString.empty();
    }

    if (mechanism == null || mechanism.length() == 0)
    {
      LocalizableMessage message = ERR_LDAPAUTH_NO_SASL_MECHANISM.get();
      throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }


    // Look at the mechanism name and call the appropriate method to process the request.
    saslMechanism = toUpperCase(mechanism);
    switch (saslMechanism)
    {
    case SASL_MECHANISM_ANONYMOUS:
      return doSASLAnonymous(bindDN, saslProperties, requestControls, responseControls);
    case SASL_MECHANISM_CRAM_MD5:
      return doSASLCRAMMD5(bindDN, bindPassword, saslProperties, requestControls, responseControls);
    case SASL_MECHANISM_DIGEST_MD5:
      return doSASLDigestMD5(bindDN, bindPassword, saslProperties, requestControls, responseControls);
    case SASL_MECHANISM_EXTERNAL:
      return doSASLExternal(bindDN, saslProperties, requestControls, responseControls);
    case SASL_MECHANISM_GSSAPI:
      return doSASLGSSAPI(bindDN, bindPassword, saslProperties, requestControls, responseControls);
    case SASL_MECHANISM_PLAIN:
      return doSASLPlain(bindDN, bindPassword, saslProperties, requestControls, responseControls);
    default:
      LocalizableMessage message = ERR_LDAPAUTH_UNSUPPORTED_SASL_MECHANISM.get(mechanism);
      throw new ClientException(ReturnCode.CLIENT_SIDE_AUTH_UNKNOWN, message);
    }
  }



  /**
   * Processes a SASL ANONYMOUS bind with the provided information.
   *
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if the authentication identity
   *                           is to be set through some other means.
   * @param  saslProperties    A set of additional properties that may be needed
   *                           to process the SASL bind.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  private String doSASLAnonymous(ByteSequence bindDN,
                     Map<String,List<String>> saslProperties,
                     List<Control> requestControls,
                     List<Control> responseControls)
         throws ClientException, LDAPException
  {
    String trace = null;

    // The only allowed property is the trace property, but it is not required.
    if (saslProperties != null)
    {
      for (Entry<String, List<String>> entry : saslProperties.entrySet())
      {
        String name = entry.getKey();
        List<String> values = entry.getValue();
        if (name.equalsIgnoreCase(SASL_PROPERTY_TRACE))
        {
          // This is acceptable, and we'll take any single value.
          trace = getSingleValue(values, ERR_LDAPAUTH_TRACE_SINGLE_VALUED);
        }
        else
        {
          LocalizableMessage message = ERR_LDAPAUTH_INVALID_SASL_PROPERTY.get(
              name, SASL_MECHANISM_ANONYMOUS);
          throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
        }
      }
    }

    // Construct the bind request and send it to the server.
    ByteString saslCredentials = trace != null ? ByteString.valueOfUtf8(trace) : null;
    sendBindRequest(SASL_MECHANISM_ANONYMOUS, bindDN, saslCredentials, requestControls);

    LDAPMessage responseMessage = readBindResponse(ERR_LDAPAUTH_CANNOT_READ_BIND_RESPONSE);
    responseControls.addAll(responseMessage.getControls());
    checkConnected(responseMessage);
    checkSuccessfulBind(responseMessage, SASL_MECHANISM_ANONYMOUS);
    return null;
  }

  /**
   * Retrieves the set of properties that a client may provide when performing a
   * SASL ANONYMOUS bind, mapped from the property names to their corresponding
   * descriptions.
   *
   * @return  The set of properties that a client may provide when performing a
   *          SASL ANONYMOUS bind, mapped from the property names to their
   *          corresponding descriptions.
   */
  private static LinkedHashMap<String, LocalizableMessage> getSASLAnonymousProperties()
  {
    LinkedHashMap<String,LocalizableMessage> properties = new LinkedHashMap<>(1);

    properties.put(SASL_PROPERTY_TRACE,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_TRACE.get());

    return properties;
  }



  /**
   * Processes a SASL CRAM-MD5 bind with the provided information.
   *
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if the authentication identity
   *                           is to be set through some other means.
   * @param  bindPassword      The password to use to bind to the Directory
   *                           Server.
   * @param  saslProperties    A set of additional properties that may be needed
   *                           to process the SASL bind.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  private String doSASLCRAMMD5(ByteSequence bindDN,
                     ByteSequence bindPassword,
                     Map<String,List<String>> saslProperties,
                     List<Control> requestControls,
                     List<Control> responseControls)
         throws ClientException, LDAPException
  {
    String authID  = null;


    // Evaluate the properties provided.  The authID is required, no other
    // properties are allowed.
    if (saslProperties == null || saslProperties.isEmpty())
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_NO_SASL_PROPERTIES.get(SASL_MECHANISM_CRAM_MD5);
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }

    for (Entry<String, List<String>> entry : saslProperties.entrySet())
    {
      String name = entry.getKey();
      List<String> values = entry.getValue();
      String lowerName = toLowerCase(name);

      if (lowerName.equals(SASL_PROPERTY_AUTHID))
      {
        authID = getSingleValue(values, ERR_LDAPAUTH_AUTHID_SINGLE_VALUED);
      }
      else
      {
        LocalizableMessage message = ERR_LDAPAUTH_INVALID_SASL_PROPERTY.get(
            name, SASL_MECHANISM_CRAM_MD5);
        throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
      }
    }


    // Make sure that the authID was provided.
    if (authID == null || authID.length() == 0)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_SASL_AUTHID_REQUIRED.get(SASL_MECHANISM_CRAM_MD5);
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }


    // Set password to ByteString.empty if the password is null.
    if (bindPassword == null)
    {
        bindPassword = ByteString.empty();
    }

    sendInitialBindRequest(SASL_MECHANISM_CRAM_MD5, bindDN);

    LDAPMessage responseMessage1 =
        readBindResponse(ERR_LDAPAUTH_CANNOT_READ_INITIAL_BIND_RESPONSE, SASL_MECHANISM_CRAM_MD5);
    checkConnected(responseMessage1);

    // Make sure that the bind response has the "SASL bind in progress" result code.
    BindResponseProtocolOp bindResponse1 =
         responseMessage1.getBindResponseProtocolOp();
    int resultCode1 = bindResponse1.getResultCode();
    if (resultCode1 != ReturnCode.SASL_BIND_IN_PROGRESS.get())
    {
      LocalizableMessage errorMessage = bindResponse1.getErrorMessage();
      if (errorMessage == null)
      {
        errorMessage = LocalizableMessage.EMPTY;
      }

      LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_INITIAL_BIND_RESPONSE.
          get(SASL_MECHANISM_CRAM_MD5, resultCode1,
              ReturnCode.get(resultCode1), errorMessage);
      throw new LDAPException(resultCode1, errorMessage, message,
                              bindResponse1.getMatchedDN(), null);
    }


    // Make sure that the bind response contains SASL credentials with the
    // challenge to use for the next stage of the bind.
    ByteString serverChallenge = bindResponse1.getServerSASLCredentials();
    if (serverChallenge == null)
    {
      LocalizableMessage message = ERR_LDAPAUTH_NO_CRAMMD5_SERVER_CREDENTIALS.get();
      throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
    }

    // Use the provided password and credentials to generate the CRAM-MD5 response.
    String salsCredentials = authID + ' ' + generateCRAMMD5Digest(bindPassword, serverChallenge);
    sendSecondBindRequest(SASL_MECHANISM_CRAM_MD5, bindDN, salsCredentials, requestControls);

    LDAPMessage responseMessage2 =
        readBindResponse(ERR_LDAPAUTH_CANNOT_READ_SECOND_BIND_RESPONSE, SASL_MECHANISM_CRAM_MD5);
    responseControls.addAll(responseMessage2.getControls());
    checkConnected(responseMessage2);
    checkSuccessfulBind(responseMessage2, SASL_MECHANISM_CRAM_MD5);
    return null;
  }

  /**
   * Construct the initial bind request to send to the server. We'll simply indicate the SASL
   * mechanism we want to use so the server will send us the challenge.
   */
  private void sendInitialBindRequest(String saslMechanism, ByteSequence bindDN) throws ClientException
  {
    // FIXME -- Should we include request controls in both stages or just the second stage?
    BindRequestProtocolOp bindRequest = new BindRequestProtocolOp(bindDN.toByteString(), saslMechanism, null);
    LDAPMessage requestMessage = new LDAPMessage(nextMessageID.getAndIncrement(), bindRequest);

    try
    {
      writer.writeMessage(requestMessage);
    }
    catch (IOException ioe)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_CANNOT_SEND_INITIAL_SASL_BIND.get(saslMechanism, getExceptionMessage(ioe));
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message, ioe);
    }
    catch (Exception e)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_CANNOT_SEND_INITIAL_SASL_BIND.get(saslMechanism, getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_ENCODING_ERROR, message, e);
    }
  }

  private LDAPMessage readBindResponse(Arg2<Object, Object> errCannotReadBindResponse, String saslMechanism)
      throws ClientException
  {
    try
    {
      LDAPMessage responseMessage = reader.readMessage();
      if (responseMessage != null)
      {
        return responseMessage;
      }
      LocalizableMessage message = ERR_LDAPAUTH_CONNECTION_CLOSED_WITHOUT_BIND_RESPONSE.get();
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message);
    }
    catch (DecodeException | LDAPException e)
    {
      LocalizableMessage message = errCannotReadBindResponse.get(saslMechanism, getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_DECODING_ERROR, message, e);
    }
    catch (IOException ioe)
    {
      LocalizableMessage message = errCannotReadBindResponse.get(saslMechanism, getExceptionMessage(ioe));
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message, ioe);
    }
    catch (Exception e)
    {
      LocalizableMessage message = errCannotReadBindResponse.get(saslMechanism, getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }
  }

  /**
   * Generates the appropriate HMAC-MD5 digest for a CRAM-MD5 authentication
   * with the given information.
   *
   * @param  password   The clear-text password to use when generating the
   *                    digest.
   * @param  challenge  The server-supplied challenge to use when generating the
   *                    digest.
   *
   * @return  The generated HMAC-MD5 digest for CRAM-MD5 authentication.
   *
   * @throws  ClientException  If a problem occurs while attempting to perform
   *                           the necessary initialization.
   */
  private String generateCRAMMD5Digest(ByteSequence password,
                                       ByteSequence challenge)
          throws ClientException
  {
    // Perform the necessary initialization if it hasn't been done yet.
    if (md5Digest == null)
    {
      try
      {
        md5Digest = MessageDigest.getInstance("MD5");
      }
      catch (Exception e)
      {
        LocalizableMessage message = ERR_LDAPAUTH_CANNOT_INITIALIZE_MD5_DIGEST.get(
            getExceptionMessage(e));
        throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR,
                message, e);
      }
    }

    if (iPad == null)
    {
      iPad = new byte[HMAC_MD5_BLOCK_LENGTH];
      oPad = new byte[HMAC_MD5_BLOCK_LENGTH];
      Arrays.fill(iPad, CRAMMD5_IPAD_BYTE);
      Arrays.fill(oPad, CRAMMD5_OPAD_BYTE);
    }


    // Get the byte arrays backing the password and challenge.
    byte[] p = password.toByteArray();
    byte[] c = challenge.toByteArray();


    // If the password is longer than the HMAC-MD5 block length, then use an
    // MD5 digest of the password rather than the password itself.
    if (password.length() > HMAC_MD5_BLOCK_LENGTH)
    {
      p = md5Digest.digest(p);
    }


    // Create byte arrays with data needed for the hash generation.
    byte[] iPadAndData = new byte[HMAC_MD5_BLOCK_LENGTH + c.length];
    System.arraycopy(iPad, 0, iPadAndData, 0, HMAC_MD5_BLOCK_LENGTH);
    System.arraycopy(c, 0, iPadAndData, HMAC_MD5_BLOCK_LENGTH, c.length);

    byte[] oPadAndHash = new byte[HMAC_MD5_BLOCK_LENGTH + MD5_DIGEST_LENGTH];
    System.arraycopy(oPad, 0, oPadAndHash, 0, HMAC_MD5_BLOCK_LENGTH);


    // Iterate through the bytes in the key and XOR them with the iPad and
    // oPad as appropriate.
    for (int i=0; i < p.length; i++)
    {
      iPadAndData[i] ^= p[i];
      oPadAndHash[i] ^= p[i];
    }


    // Copy an MD5 digest of the iPad-XORed key and the data into the array to
    // be hashed.
    System.arraycopy(md5Digest.digest(iPadAndData), 0, oPadAndHash,
                     HMAC_MD5_BLOCK_LENGTH, MD5_DIGEST_LENGTH);


    // Calculate an MD5 digest of the resulting array and get the corresponding
    // hex string representation.
    byte[] digestBytes = md5Digest.digest(oPadAndHash);

    StringBuilder hexDigest = new StringBuilder(2*digestBytes.length);
    for (byte b : digestBytes)
    {
      hexDigest.append(byteToLowerHex(b));
    }

    return hexDigest.toString();
  }



  /**
   * Retrieves the set of properties that a client may provide when performing a
   * SASL CRAM-MD5 bind, mapped from the property names to their corresponding
   * descriptions.
   *
   * @return  The set of properties that a client may provide when performing a
   *          SASL CRAM-MD5 bind, mapped from the property names to their
   *          corresponding descriptions.
   */
  private static LinkedHashMap<String, LocalizableMessage> getSASLCRAMMD5Properties()
  {
    LinkedHashMap<String,LocalizableMessage> properties = new LinkedHashMap<>(1);

    properties.put(SASL_PROPERTY_AUTHID,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_AUTHID.get());

    return properties;
  }



  /**
   * Processes a SASL DIGEST-MD5 bind with the provided information.
   *
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if the authentication identity
   *                           is to be set through some other means.
   * @param  bindPassword      The password to use to bind to the Directory
   *                           Server.
   * @param  saslProperties    A set of additional properties that may be needed
   *                           to process the SASL bind.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  private String doSASLDigestMD5(ByteSequence bindDN,
                     ByteSequence bindPassword,
                     Map<String,List<String>> saslProperties,
                     List<Control> requestControls,
                     List<Control> responseControls)
         throws ClientException, LDAPException
  {
    String  authID               = null;
    String  realm                = null;
    String  qop                  = "auth";
    String  digestURI            = "ldap/" + hostName;
    String  authzID              = null;
    boolean realmSetFromProperty = false;


    // Evaluate the properties provided.  The authID is required.  The realm,
    // QoP, digest URI, and authzID are optional.
    if (saslProperties == null || saslProperties.isEmpty())
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_NO_SASL_PROPERTIES.get(SASL_MECHANISM_DIGEST_MD5);
      throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }

    for (Entry<String, List<String>> entry : saslProperties.entrySet())
    {
      String name = entry.getKey();
      List<String> values = entry.getValue();
      String lowerName = toLowerCase(name);

      if (lowerName.equals(SASL_PROPERTY_AUTHID))
      {
        authID = getSingleValue(values, ERR_LDAPAUTH_AUTHID_SINGLE_VALUED);
      }
      else if (lowerName.equals(SASL_PROPERTY_REALM))
      {
        Iterator<String> iterator = values.iterator();
        if (iterator.hasNext())
        {
          realm                = iterator.next();
          realmSetFromProperty = true;

          if (iterator.hasNext())
          {
            LocalizableMessage message = ERR_LDAPAUTH_REALM_SINGLE_VALUED.get();
            throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                      message);
          }
        }
      }
      else if (lowerName.equals(SASL_PROPERTY_QOP))
      {
        Iterator<String> iterator = values.iterator();
        if (iterator.hasNext())
        {
          qop = toLowerCase(iterator.next());

          if (iterator.hasNext())
          {
            LocalizableMessage message = ERR_LDAPAUTH_QOP_SINGLE_VALUED.get();
            throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                      message);
          }

          if (qop.equals("auth"))
          {
            // This is always fine.
          }
          else if (qop.equals("auth-int") || qop.equals("auth-conf"))
          {
            // FIXME -- Add support for integrity and confidentiality.
            LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_QOP_NOT_SUPPORTED.get(qop);
            throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                      message);
          }
          else
          {
            // This is an illegal value.
            LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_INVALID_QOP.get(qop);
            throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                      message);
          }
        }
      }
      else if (lowerName.equals(SASL_PROPERTY_DIGEST_URI))
      {
        digestURI = toLowerCase(getSingleValue(values, ERR_LDAPAUTH_DIGEST_URI_SINGLE_VALUED));
      }
      else if (lowerName.equals(SASL_PROPERTY_AUTHZID))
      {
        authzID = toLowerCase(getSingleValue(values, ERR_LDAPAUTH_AUTHZID_SINGLE_VALUED));
      }
      else
      {
        LocalizableMessage message = ERR_LDAPAUTH_INVALID_SASL_PROPERTY.get(
            name, SASL_MECHANISM_DIGEST_MD5);
        throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
      }
    }


    // Make sure that the authID was provided.
    if (authID == null || authID.length() == 0)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_SASL_AUTHID_REQUIRED.get(SASL_MECHANISM_DIGEST_MD5);
      throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
              message);
    }


    // Set password to ByteString.empty if the password is null.
    if (bindPassword == null)
    {
        bindPassword = ByteString.empty();
    }


    sendInitialBindRequest(SASL_MECHANISM_DIGEST_MD5, bindDN);

    LDAPMessage responseMessage1 =
        readBindResponse(ERR_LDAPAUTH_CANNOT_READ_INITIAL_BIND_RESPONSE, SASL_MECHANISM_DIGEST_MD5);
    checkConnected(responseMessage1);

    // Make sure that the bind response has the "SASL bind in progress" result code.
    BindResponseProtocolOp bindResponse1 =
         responseMessage1.getBindResponseProtocolOp();
    int resultCode1 = bindResponse1.getResultCode();
    if (resultCode1 != ReturnCode.SASL_BIND_IN_PROGRESS.get())
    {
      LocalizableMessage errorMessage = bindResponse1.getErrorMessage();
      if (errorMessage == null)
      {
        errorMessage = LocalizableMessage.EMPTY;
      }

      LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_INITIAL_BIND_RESPONSE.
          get(SASL_MECHANISM_DIGEST_MD5, resultCode1,
              ReturnCode.get(resultCode1), errorMessage);
      throw new LDAPException(resultCode1, errorMessage, message,
                              bindResponse1.getMatchedDN(), null);
    }


    // Make sure that the bind response contains SASL credentials with the
    // information to use for the next stage of the bind.
    ByteString serverCredentials =
         bindResponse1.getServerSASLCredentials();
    if (serverCredentials == null)
    {
      LocalizableMessage message = ERR_LDAPAUTH_NO_DIGESTMD5_SERVER_CREDENTIALS.get();
      throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
    }


    // Parse the server SASL credentials to get the necessary information.  In
    // particular, look at the realm, the nonce, the QoP modes, and the charset.
    // We'll only care about the realm if none was provided in the SASL
    // properties and only one was provided in the server SASL credentials.
    String  credString = serverCredentials.toString();
    String  lowerCreds = toLowerCase(credString);
    String  nonce      = null;
    boolean useUTF8    = false;
    int     pos        = 0;
    int     length     = credString.length();
    while (pos < length)
    {
      int equalPos = credString.indexOf('=', pos+1);
      if (equalPos < 0)
      {
        // This is bad because we're not at the end of the string but we don't
        // have a name/value delimiter.
        LocalizableMessage message =
            ERR_LDAPAUTH_DIGESTMD5_INVALID_TOKEN_IN_CREDENTIALS.get(
                    credString, pos);
        throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
      }


      String tokenName  = lowerCreds.substring(pos, equalPos);

      StringBuilder valueBuffer = new StringBuilder();
      pos = readToken(credString, equalPos+1, length, valueBuffer);
      String tokenValue = valueBuffer.toString();

      if (tokenName.equals("charset"))
      {
        // The value must be the string "utf-8".  If not, that's an error.
        if (! tokenValue.equalsIgnoreCase("utf-8"))
        {
          LocalizableMessage message =
              ERR_LDAPAUTH_DIGESTMD5_INVALID_CHARSET.get(tokenValue);
          throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
        }

        useUTF8 = true;
      }
      else if (tokenName.equals("realm"))
      {
        // This will only be of interest to us if there is only a single realm
        // in the server credentials and none was provided as a client-side
        // property.
        if (! realmSetFromProperty)
        {
          if (realm == null)
          {
            // No other realm was specified, so we'll use this one for now.
            realm = tokenValue;
          }
          else
          {
            // This must mean that there are multiple realms in the server
            // credentials.  In that case, we'll not provide any realm at all.
            // To make sure that happens, pretend that the client specified the
            // realm.
            realm                = null;
            realmSetFromProperty = true;
          }
        }
      }
      else if (tokenName.equals("nonce"))
      {
        nonce = tokenValue;
      }
      else if (tokenName.equals("qop"))
      {
        // The QoP modes provided by the server should be a comma-delimited
        // list.  Decode that list and make sure the QoP we have chosen is in
        // that list.
        StringTokenizer tokenizer = new StringTokenizer(tokenValue, ",");
        LinkedList<String> qopModes = new LinkedList<>();
        while (tokenizer.hasMoreTokens())
        {
          qopModes.add(toLowerCase(tokenizer.nextToken().trim()));
        }

        if (! qopModes.contains(qop))
        {
          LocalizableMessage message = ERR_LDAPAUTH_REQUESTED_QOP_NOT_SUPPORTED_BY_SERVER.
              get(qop, tokenValue);
          throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                    message);
        }
      }
      else
      {
        // Other values may have been provided, but they aren't of interest to
        // us because they shouldn't change anything about the way we encode the
        // second part of the request.  Rather than attempt to examine them,
        // we'll assume that the server sent a valid response.
      }
    }


    // Make sure that the nonce was included in the response from the server.
    if (nonce == null)
    {
      LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_NO_NONCE.get();
      throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
    }


    // Generate the cnonce that we will use for this request.
    String cnonce = generateCNonce();


    // Generate the response digest, and initialize the necessary remaining
    // variables to use in the generation of that digest.
    String nonceCount = "00000001";
    String charset    = useUTF8 ? "UTF-8" : "ISO-8859-1";
    String responseDigest;
    try
    {
      responseDigest = generateDigestMD5Response(authID, authzID,
                                                 bindPassword, realm,
                                                 nonce, cnonce, nonceCount,
                                                 digestURI, qop, charset);
    }
    catch (ClientException ce)
    {
      throw ce;
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_CANNOT_CREATE_RESPONSE_DIGEST.
          get(getExceptionMessage(e));
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }


    // Generate the SASL credentials for the second bind request.
    StringBuilder credBuffer = new StringBuilder();
    credBuffer.append("username=\"").append(authID).append("\"");
    if (realm != null)
    {
      credBuffer.append(",realm=\"").append(realm).append("\"");
    }
    credBuffer.append(",nonce=\"").append(nonce);
    credBuffer.append("\",cnonce=\"").append(cnonce);
    credBuffer.append("\",nc=").append(nonceCount);
    credBuffer.append(",qop=").append(qop);
    credBuffer.append(",digest-uri=\"").append(digestURI);
    credBuffer.append("\",response=").append(responseDigest);
    if (useUTF8)
    {
      credBuffer.append(",charset=utf-8");
    }
    if (authzID != null)
    {
      credBuffer.append(",authzid=\"").append(authzID).append("\"");
    }

    sendSecondBindRequest(SASL_MECHANISM_DIGEST_MD5, bindDN, credBuffer.toString(), requestControls);

    LDAPMessage responseMessage2 =
        readBindResponse(ERR_LDAPAUTH_CANNOT_READ_SECOND_BIND_RESPONSE, SASL_MECHANISM_DIGEST_MD5);
    responseControls.addAll(responseMessage2.getControls());
    checkConnected(responseMessage2);
    BindResponseProtocolOp bindResponse2 = checkSuccessfulBind(responseMessage2, SASL_MECHANISM_DIGEST_MD5);


    // Make sure that the bind response included server SASL credentials with
    // the appropriate rspauth value.
    ByteString rspAuthCreds = bindResponse2.getServerSASLCredentials();
    if (rspAuthCreds == null)
    {
      LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_NO_RSPAUTH_CREDS.get();
      throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
    }

    String credStr = toLowerCase(rspAuthCreds.toString());
    if (! credStr.startsWith("rspauth="))
    {
      LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_NO_RSPAUTH_CREDS.get();
      throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
    }


    byte[] serverRspAuth;
    try
    {
      serverRspAuth = hexStringToByteArray(credStr.substring(8));
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_COULD_NOT_DECODE_RSPAUTH.get(
          getExceptionMessage(e));
      throw new LDAPException(ReturnCode.PROTOCOL_ERROR.get(), message);
    }

    byte[] clientRspAuth;
    try
    {
      clientRspAuth =
           generateDigestMD5RspAuth(authID, authzID, bindPassword,
                                    realm, nonce, cnonce, nonceCount, digestURI,
                                    qop, charset);
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_COULD_NOT_CALCULATE_RSPAUTH.get(
          getExceptionMessage(e));
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
    }

    if (! Arrays.equals(serverRspAuth, clientRspAuth))
    {
      LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_RSPAUTH_MISMATCH.get();
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
    }

    // FIXME -- Need to look for things like password expiration warning, reset notice, etc.
    return null;
  }

  private void sendSecondBindRequest(String saslMechanism, ByteSequence bindDN, String saslCredentials,
      List<Control> requestControls) throws ClientException
  {
    // Generate and send the second bind request.
    BindRequestProtocolOp bindRequest2 =
        new BindRequestProtocolOp(bindDN.toByteString(), saslMechanism, ByteString.valueOfUtf8(saslCredentials));
    LDAPMessage requestMessage2 = new LDAPMessage(nextMessageID.getAndIncrement(), bindRequest2, requestControls);

    try
    {
      writer.writeMessage(requestMessage2);
    }
    catch (IOException ioe)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_CANNOT_SEND_SECOND_SASL_BIND.get(saslMechanism, getExceptionMessage(ioe));
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message, ioe);
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_CANNOT_SEND_SECOND_SASL_BIND.get(saslMechanism, getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_ENCODING_ERROR, message, e);
    }
  }

  /**
   * Reads the next token from the provided credentials string using the
   * provided information.  If the token is surrounded by quotation marks, then
   * the token returned will not include those quotation marks.
   *
   * @param  credentials  The credentials string from which to read the token.
   * @param  startPos     The position of the first character of the token to
   *                      read.
   * @param  length       The total number of characters in the credentials
   *                      string.
   * @param  token        The buffer into which the token is to be placed.
   *
   * @return  The position at which the next token should start, or a value
   *          greater than or equal to the length of the string if there are no
   *          more tokens.
   *
   * @throws  LDAPException  If a problem occurs while attempting to read the
   *                         token.
   */
  private int readToken(String credentials, int startPos, int length,
                        StringBuilder token)
          throws LDAPException
  {
    // If the position is greater than or equal to the length, then we shouldn't
    // do anything.
    if (startPos >= length)
    {
      return startPos;
    }


    // Look at the first character to see if it's an empty string or the string
    // is quoted.
    boolean isEscaped = false;
    boolean isQuoted  = false;
    int     pos       = startPos;
    char    c         = credentials.charAt(pos++);

    if (c == ',')
    {
      // This must be a zero-length token, so we'll just return the next
      // position.
      return pos;
    }
    else if (c == '"')
    {
      // The string is quoted, so we'll ignore this character, and we'll keep
      // reading until we find the unescaped closing quote followed by a comma
      // or the end of the string.
      isQuoted = true;
    }
    else if (c == '\\')
    {
      // The next character is escaped, so we'll take it no matter what.
      isEscaped = true;
    }
    else
    {
      // The string is not quoted, and this is the first character.  Store this
      // character and keep reading until we find a comma or the end of the
      // string.
      token.append(c);
    }


    // Enter a loop, reading until we find the appropriate criteria for the end
    // of the token.
    while (pos < length)
    {
      c = credentials.charAt(pos++);

      if (isEscaped)
      {
        // The previous character was an escape, so we'll take this no matter
        // what.
        token.append(c);
        isEscaped = false;
      }
      else if (c == ',')
      {
        // If this is a quoted string, then this comma is part of the token.
        // Otherwise, it's the end of the token.
        if (!isQuoted)
        {
          break;
        }
        token.append(c);
      }
      else if (c == '"')
      {
        if (isQuoted)
        {
          // This should be the end of the token, but in order for it to be
          // valid it must be followed by a comma or the end of the string.
          if (pos >= length)
          {
            // We have hit the end of the string, so this is fine.
            break;
          }
          char c2 = credentials.charAt(pos++);
          if (c2 == ',')
          {
            // We have hit the end of the token, so this is fine.
            break;
          }
          else
          {
            // We found the closing quote before the end of the token. This is not fine.
            LocalizableMessage message = ERR_LDAPAUTH_DIGESTMD5_INVALID_CLOSING_QUOTE_POS.get(pos - 2);
            throw new LDAPException(ReturnCode.INVALID_CREDENTIALS.get(), message);
          }
        }
        else
        {
          // This must be part of the value, so we'll take it.
          token.append(c);
        }
      }
      else if (c == '\\')
      {
        // The next character is escaped.  We'll set a flag so we know to
        // accept it, but will not include the backspace itself.
        isEscaped = true;
      }
      else
      {
        token.append(c);
      }
    }


    return pos;
  }



  /**
   * Generates a cnonce value to use during the DIGEST-MD5 authentication
   * process.
   *
   * @return  The cnonce that should be used for DIGEST-MD5 authentication.
   */
  private String generateCNonce()
  {
    if (secureRandom == null)
    {
      secureRandom = new SecureRandom();
    }

    byte[] cnonceBytes = new byte[16];
    secureRandom.nextBytes(cnonceBytes);

    return Base64.encode(cnonceBytes);
  }



  /**
   * Generates the appropriate DIGEST-MD5 response for the provided set of
   * information.
   *
   * @param  authID    The username from the authentication request.
   * @param  authzID     The authorization ID from the request, or
   *                     <CODE>null</CODE> if there is none.
   * @param  password    The clear-text password for the user.
   * @param  realm       The realm for which the authentication is to be
   *                     performed.
   * @param  nonce       The random data generated by the server for use in the
   *                     digest.
   * @param  cnonce      The random data generated by the client for use in the
   *                     digest.
   * @param  nonceCount  The 8-digit hex string indicating the number of times
   *                     the provided nonce has been used by the client.
   * @param  digestURI   The digest URI that specifies the service and host for
   *                     which the authentication is being performed.
   * @param  qop         The quality of protection string for the
   *                     authentication.
   * @param  charset     The character set used to encode the information.
   *
   * @return  The DIGEST-MD5 response for the provided set of information.
   *
   * @throws  ClientException  If a problem occurs while attempting to
   *                           initialize the MD5 digest.
   *
   * @throws  UnsupportedEncodingException  If the specified character set is
   *                                        invalid for some reason.
   */
  private String generateDigestMD5Response(String authID, String authzID,
                                           ByteSequence password, String realm,
                                           String nonce, String cnonce,
                                           String nonceCount, String digestURI,
                                           String qop, String charset)
          throws ClientException, UnsupportedEncodingException
  {
    // Perform the necessary initialization if it hasn't been done yet.
    if (md5Digest == null)
    {
      try
      {
        md5Digest = MessageDigest.getInstance("MD5");
      }
      catch (Exception e)
      {
        LocalizableMessage message = ERR_LDAPAUTH_CANNOT_INITIALIZE_MD5_DIGEST.get(
            getExceptionMessage(e));
        throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR,
                message, e);
      }
    }

    // Get a hash of "username:realm:password".
    String a1String1 = authID + ':' + ((realm == null) ? "" : realm) + ':';
    byte[] a1Bytes1a = a1String1.getBytes(charset);
    byte[] a1Bytes1  = new byte[a1Bytes1a.length + password.length()];
    System.arraycopy(a1Bytes1a, 0, a1Bytes1, 0, a1Bytes1a.length);
    password.copyTo(a1Bytes1, a1Bytes1a.length);
    byte[] urpHash = md5Digest.digest(a1Bytes1);

    // Next, get a hash of "urpHash:nonce:cnonce[:authzid]".
    StringBuilder a1String2 = new StringBuilder();
    a1String2.append(':');
    a1String2.append(nonce);
    a1String2.append(':');
    a1String2.append(cnonce);
    if (authzID != null)
    {
      a1String2.append(':');
      a1String2.append(authzID);
    }
    byte[] a1Bytes2a = a1String2.toString().getBytes(charset);
    byte[] a1Bytes2  = new byte[urpHash.length + a1Bytes2a.length];
    System.arraycopy(urpHash, 0, a1Bytes2, 0, urpHash.length);
    System.arraycopy(a1Bytes2a, 0, a1Bytes2, urpHash.length, a1Bytes2a.length);
    byte[] a1Hash = md5Digest.digest(a1Bytes2);

    // Next, get a hash of "AUTHENTICATE:digesturi".
    byte[] a2Bytes = ("AUTHENTICATE:" + digestURI).getBytes(charset);
    byte[] a2Hash  = md5Digest.digest(a2Bytes);

    // Get hex string representations of the last two hashes.
    String a1HashHex = getHexString(a1Hash);
    String a2HashHex = getHexString(a2Hash);

    // Put together the final string to hash, consisting of
    // "a1HashHex:nonce:nonceCount:cnonce:qop:a2HashHex" and get its digest.
    String kdStr = a1HashHex + ':' + nonce + ':' + nonceCount + ':' + cnonce + ':' + qop + ':' + a2HashHex;
    return getHexString(md5Digest.digest(kdStr.getBytes(charset)));
  }

  /**
   * Generates the appropriate DIGEST-MD5 rspauth digest using the provided
   * information.
   *
   * @param  authID      The username from the authentication request.
   * @param  authzID     The authorization ID from the request, or
   *                     <CODE>null</CODE> if there is none.
   * @param  password    The clear-text password for the user.
   * @param  realm       The realm for which the authentication is to be
   *                     performed.
   * @param  nonce       The random data generated by the server for use in the
   *                     digest.
   * @param  cnonce      The random data generated by the client for use in the
   *                     digest.
   * @param  nonceCount  The 8-digit hex string indicating the number of times
   *                     the provided nonce has been used by the client.
   * @param  digestURI   The digest URI that specifies the service and host for
   *                     which the authentication is being performed.
   * @param  qop         The quality of protection string for the
   *                     authentication.
   * @param  charset     The character set used to encode the information.
   *
   * @return  The DIGEST-MD5 response for the provided set of information.
   *
   * @throws  UnsupportedEncodingException  If the specified character set is
   *                                        invalid for some reason.
   */
  private byte[] generateDigestMD5RspAuth(String authID, String authzID,
                                         ByteSequence password, String realm,
                                         String nonce, String cnonce,
                                         String nonceCount, String digestURI,
                                         String qop, String charset)
         throws UnsupportedEncodingException
  {
    // First, get a hash of "username:realm:password".
    String a1String1 = authID + ':' + realm + ':';

    byte[] a1Bytes1a = a1String1.getBytes(charset);
    byte[] a1Bytes1  = new byte[a1Bytes1a.length + password.length()];
    System.arraycopy(a1Bytes1a, 0, a1Bytes1, 0, a1Bytes1a.length);
    password.copyTo(a1Bytes1, a1Bytes1a.length);
    byte[] urpHash = md5Digest.digest(a1Bytes1);


    // Next, get a hash of "urpHash:nonce:cnonce[:authzid]".
    StringBuilder a1String2 = new StringBuilder();
    a1String2.append(':');
    a1String2.append(nonce);
    a1String2.append(':');
    a1String2.append(cnonce);
    if (authzID != null)
    {
      a1String2.append(':');
      a1String2.append(authzID);
    }
    byte[] a1Bytes2a = a1String2.toString().getBytes(charset);
    byte[] a1Bytes2  = new byte[urpHash.length + a1Bytes2a.length];
    System.arraycopy(urpHash, 0, a1Bytes2, 0, urpHash.length);
    System.arraycopy(a1Bytes2a, 0, a1Bytes2, urpHash.length,
                     a1Bytes2a.length);
    byte[] a1Hash = md5Digest.digest(a1Bytes2);


    // Next, get a hash of "AUTHENTICATE:digesturi".
    String a2String = ":" + digestURI;
    if (qop.equals("auth-int") || qop.equals("auth-conf"))
    {
      a2String += ":00000000000000000000000000000000";
    }
    byte[] a2Bytes = a2String.getBytes(charset);
    byte[] a2Hash  = md5Digest.digest(a2Bytes);


    // Get hex string representations of the last two hashes.
    String a1HashHex = getHexString(a1Hash);
    String a2HashHex = getHexString(a2Hash);

    // Put together the final string to hash, consisting of
    // "a1HashHex:nonce:nonceCount:cnonce:qop:a2HashHex" and get its digest.
    String kdStr = a1HashHex + ':' + nonce + ':' + nonceCount + ':' + cnonce + ':' + qop + ':' + a2HashHex;
    return md5Digest.digest(kdStr.getBytes(charset));
  }

  /**
   * Retrieves a hexadecimal string representation of the contents of the
   * provided byte array.
   *
   * @param  byteArray  The byte array for which to obtain the hexadecimal
   *                    string representation.
   *
   * @return  The hexadecimal string representation of the contents of the
   *          provided byte array.
   */
  private String getHexString(byte[] byteArray)
  {
    StringBuilder buffer = new StringBuilder(2*byteArray.length);
    for (byte b : byteArray)
    {
      buffer.append(byteToLowerHex(b));
    }

    return buffer.toString();
  }



  /**
   * Retrieves the set of properties that a client may provide when performing a
   * SASL DIGEST-MD5 bind, mapped from the property names to their corresponding
   * descriptions.
   *
   * @return  The set of properties that a client may provide when performing a
   *          SASL DIGEST-MD5 bind, mapped from the property names to their
   *          corresponding descriptions.
   */
  private static LinkedHashMap<String, LocalizableMessage> getSASLDigestMD5Properties()
  {
    LinkedHashMap<String,LocalizableMessage> properties = new LinkedHashMap<>(5);

    properties.put(SASL_PROPERTY_AUTHID,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_AUTHID.get());
    properties.put(SASL_PROPERTY_REALM,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_REALM.get());
    properties.put(SASL_PROPERTY_QOP,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_QOP.get());
    properties.put(SASL_PROPERTY_DIGEST_URI,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_DIGEST_URI.get());
    properties.put(SASL_PROPERTY_AUTHZID,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_AUTHZID.get());

    return properties;
  }



  /**
   * Processes a SASL EXTERNAL bind with the provided information.
   *
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if the authentication identity
   *                           is to be set through some other means.
   * @param  saslProperties    A set of additional properties that may be needed
   *                           to process the SASL bind.  SASL EXTERNAL does not
   *                           take any properties, so this should be empty or
   *                           <CODE>null</CODE>.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  public String doSASLExternal(ByteSequence bindDN,
                     Map<String,List<String>> saslProperties,
                     List<Control> requestControls,
                     List<Control> responseControls)
         throws ClientException, LDAPException
  {
    // Make sure that no SASL properties were provided.
    if (saslProperties != null && ! saslProperties.isEmpty())
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_NO_ALLOWED_SASL_PROPERTIES.get(SASL_MECHANISM_EXTERNAL);
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }


    sendBindRequest(SASL_MECHANISM_EXTERNAL, bindDN, null, requestControls);

    LDAPMessage responseMessage = readBindResponse(ERR_LDAPAUTH_CANNOT_READ_BIND_RESPONSE);
    responseControls.addAll(responseMessage.getControls());
    checkConnected(responseMessage);

    BindResponseProtocolOp bindResponse =
         responseMessage.getBindResponseProtocolOp();
    int resultCode = bindResponse.getResultCode();
    if (resultCode == ReturnCode.SUCCESS.get())
    {
      // FIXME -- Need to look for things like password expiration warning,
      // reset notice, etc.
      return null;
    }

    // FIXME -- Add support for referrals.

    LocalizableMessage message =
        ERR_LDAPAUTH_SASL_BIND_FAILED.get(SASL_MECHANISM_EXTERNAL);
    throw new LDAPException(resultCode, bindResponse.getErrorMessage(),
                            message, bindResponse.getMatchedDN(), null);
  }

  private void sendBindRequest(String saslMechanism, ByteSequence bindDN, ByteString saslCredentials,
      List<Control> requestControls) throws ClientException
  {
    BindRequestProtocolOp bindRequest =
        new BindRequestProtocolOp(bindDN.toByteString(), saslMechanism, saslCredentials);
    LDAPMessage requestMessage = new LDAPMessage(nextMessageID.getAndIncrement(), bindRequest, requestControls);

    try
    {
      writer.writeMessage(requestMessage);
    }
    catch (IOException ioe)
    {
      LocalizableMessage message = ERR_LDAPAUTH_CANNOT_SEND_SASL_BIND.get(saslMechanism, getExceptionMessage(ioe));
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message, ioe);
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_CANNOT_SEND_SASL_BIND.get(saslMechanism, getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_ENCODING_ERROR, message, e);
    }
  }

  private LDAPMessage readBindResponse(Arg1<Object> errCannotReadBindResponse) throws ClientException
  {
    try
    {
      LDAPMessage responseMessage = reader.readMessage();
      if (responseMessage != null)
      {
        return responseMessage;
      }
      LocalizableMessage message = ERR_LDAPAUTH_CONNECTION_CLOSED_WITHOUT_BIND_RESPONSE.get();
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message);
    }
    catch (DecodeException | LDAPException e)
    {
      LocalizableMessage message = errCannotReadBindResponse.get(getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_DECODING_ERROR, message, e);
    }
    catch (IOException ioe)
    {
      LocalizableMessage message = errCannotReadBindResponse.get(getExceptionMessage(ioe));
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN, message, ioe);
    }
    catch (Exception e)
    {
      LocalizableMessage message = errCannotReadBindResponse.get(getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }
  }

  /**
   * Retrieves the set of properties that a client may provide when performing a
   * SASL EXTERNAL bind, mapped from the property names to their corresponding
   * descriptions.
   *
   * @return  The set of properties that a client may provide when performing a
   *          SASL EXTERNAL bind, mapped from the property names to their
   *          corresponding descriptions.
   */
  private static LinkedHashMap<String, LocalizableMessage> getSASLExternalProperties()
  {
    // There are no properties for the SASL EXTERNAL mechanism.
    return new LinkedHashMap<>(0);
  }



  /**
   * Processes a SASL GSSAPI bind with the provided information.
   *
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if the authentication identity
   *                           is to be set through some other means.
   * @param  bindPassword      The password to use to bind to the Directory
   *                           Server.
   * @param  saslProperties    A set of additional properties that may be needed
   *                           to process the SASL bind.  SASL EXTERNAL does not
   *                           take any properties, so this should be empty or
   *                           <CODE>null</CODE>.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  private String doSASLGSSAPI(ByteSequence bindDN,
                     ByteSequence bindPassword,
                     Map<String,List<String>> saslProperties,
                     List<Control> requestControls,
                     List<Control> responseControls)
         throws ClientException, LDAPException
  {
    String kdc     = null;
    String realm   = null;

    gssapiBindDN  = bindDN;
    gssapiAuthID  = null;
    gssapiAuthzID = null;
    gssapiQoP     = "auth";
    gssapiAuthPW = bindPassword != null ? bindPassword.toString().toCharArray() : null;

    // Evaluate the properties provided.  The authID is required.  The authzID,
    // KDC, QoP, and realm are optional.
    if (saslProperties == null || saslProperties.isEmpty())
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_NO_SASL_PROPERTIES.get(SASL_MECHANISM_GSSAPI);
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }

    for (Entry<String, List<String>> entry : saslProperties.entrySet())
    {
      String name = entry.getKey();
      String lowerName = toLowerCase(name);
      List<String> values = entry.getValue();

      if (lowerName.equals(SASL_PROPERTY_AUTHID))
      {
        gssapiAuthID = getSingleValue(values, ERR_LDAPAUTH_AUTHID_SINGLE_VALUED);
      }
      else if (lowerName.equals(SASL_PROPERTY_AUTHZID))
      {
        gssapiAuthzID = getSingleValue(values, ERR_LDAPAUTH_AUTHZID_SINGLE_VALUED);
      }
      else if (lowerName.equals(SASL_PROPERTY_KDC))
      {
        kdc = getSingleValue(values, ERR_LDAPAUTH_KDC_SINGLE_VALUED);
      }
      else if (lowerName.equals(SASL_PROPERTY_QOP))
      {
        Iterator<String> iterator = values.iterator();
        if (iterator.hasNext())
        {
          gssapiQoP = toLowerCase(iterator.next());

          if (iterator.hasNext())
          {
            LocalizableMessage message = ERR_LDAPAUTH_QOP_SINGLE_VALUED.get();
            throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                      message);
          }

          if (gssapiQoP.equals("auth"))
          {
            // This is always fine.
          }
          else if (gssapiQoP.equals("auth-int") ||
                   gssapiQoP.equals("auth-conf"))
          {
            // FIXME -- Add support for integrity and confidentiality.
            LocalizableMessage message =
                ERR_LDAPAUTH_DIGESTMD5_QOP_NOT_SUPPORTED.get(gssapiQoP);
            throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                      message);
          }
          else
          {
            // This is an illegal value.
            LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_INVALID_QOP.get(gssapiQoP);
            throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR,
                                      message);
          }
        }
      }
      else if (lowerName.equals(SASL_PROPERTY_REALM))
      {
        realm = getSingleValue(values, ERR_LDAPAUTH_REALM_SINGLE_VALUED);
      }
      else
      {
        LocalizableMessage message =
            ERR_LDAPAUTH_INVALID_SASL_PROPERTY.get(name, SASL_MECHANISM_GSSAPI);
        throw new ClientException(
                ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
      }
    }


    // Make sure that the authID was provided.
    if (gssapiAuthID == null || gssapiAuthID.length() == 0)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_SASL_AUTHID_REQUIRED.get(SASL_MECHANISM_GSSAPI);
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }


    // See if an authzID was provided.  If not, then use the authID.
    if (gssapiAuthzID == null)
    {
      gssapiAuthzID = gssapiAuthID;
    }


    // See if the realm and/or KDC were specified.  If so, then set properties
    // that will allow them to be used.  Otherwise, we'll hope that the
    // underlying system has a valid Kerberos client configuration.
    if (realm != null)
    {
      System.setProperty(KRBV_PROPERTY_REALM, realm);
    }

    if (kdc != null)
    {
      System.setProperty(KRBV_PROPERTY_KDC, kdc);
    }


    // Since we're going to be using JAAS behind the scenes, we need to have a
    // JAAS configuration.  Rather than always requiring the user to provide it,
    // we'll write one to a temporary file that will be deleted when the JVM
    // exits.
    String configFileName;
    try
    {
      File tempFile = File.createTempFile("login", "conf");
      configFileName = tempFile.getAbsolutePath();
      tempFile.deleteOnExit();
      try (BufferedWriter w = new BufferedWriter(new FileWriter(tempFile, false))) {
        w.write(getClass().getName() + " {");
        w.newLine();

        w.write("  com.sun.security.auth.module.Krb5LoginModule required " +
            "client=TRUE useTicketCache=TRUE;");
        w.newLine();

        w.write("};");
        w.newLine();
      }
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_CANNOT_CREATE_JAAS_CONFIG.get(
          getExceptionMessage(e));
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }

    System.setProperty(JAAS_PROPERTY_CONFIG_FILE, configFileName);
    System.setProperty(JAAS_PROPERTY_SUBJECT_CREDS_ONLY, "true");


    // The rest of this code must be executed via JAAS, so it will have to go
    // in the "run" method.
    LoginContext loginContext;
    try
    {
      loginContext = new LoginContext(getClass().getName(), this);
      loginContext.login();
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_LOCAL_AUTHENTICATION_FAILED.get(
          getExceptionMessage(e));
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }

    try
    {
      Subject.doAs(loginContext.getSubject(), this);
    }
    catch (Exception e)
    {
      if (e instanceof ClientException)
      {
        throw (ClientException) e;
      }
      else if (e instanceof LDAPException)
      {
        throw (LDAPException) e;
      }

      LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_REMOTE_AUTHENTICATION_FAILED.get(
              getExceptionMessage(e));
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }


    // FIXME -- Need to make sure we handle request and response controls properly,
    // and also check for any possible message to send back to the client.
    return null;
  }

  private String getSingleValue(List<String> values, Arg0 singleValuedErrMsg) throws ClientException
  {
    Iterator<String> it = values.iterator();
    if (it.hasNext())
    {
      String result = it.next();
      if (it.hasNext())
      {
        throw new ClientException(ReturnCode.CLIENT_SIDE_PARAM_ERROR, singleValuedErrMsg.get());
      }
      return result;
    }
    return null;
  }

  /**
   * Retrieves the set of properties that a client may provide when performing a
   * SASL EXTERNAL bind, mapped from the property names to their corresponding
   * descriptions.
   *
   * @return  The set of properties that a client may provide when performing a
   *          SASL EXTERNAL bind, mapped from the property names to their
   *          corresponding descriptions.
   */
  private static LinkedHashMap<String, LocalizableMessage> getSASLGSSAPIProperties()
  {
    LinkedHashMap<String,LocalizableMessage> properties = new LinkedHashMap<>(4);

    properties.put(SASL_PROPERTY_AUTHID,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_AUTHID.get());
    properties.put(SASL_PROPERTY_AUTHZID,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_AUTHZID.get());
    properties.put(SASL_PROPERTY_KDC,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_KDC.get());
    properties.put(SASL_PROPERTY_REALM,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_REALM.get());

    return properties;
  }



  /**
   * Processes a SASL PLAIN bind with the provided information.
   *
   * @param  bindDN            The DN to use to bind to the Directory Server, or
   *                           <CODE>null</CODE> if the authentication identity
   *                           is to be set through some other means.
   * @param  bindPassword      The password to use to bind to the Directory
   *                           Server.
   * @param  saslProperties    A set of additional properties that may be needed
   *                           to process the SASL bind.
   * @param  requestControls   The set of controls to include the request to the
   *                           server.
   * @param  responseControls  A list to hold the set of controls included in
   *                           the response from the server.
   *
   * @return  A message providing additional information about the bind if
   *          appropriate, or <CODE>null</CODE> if there is no special
   *          information available.
   *
   * @throws  ClientException  If a client-side problem prevents the bind
   *                           attempt from succeeding.
   *
   * @throws  LDAPException  If the bind fails or some other server-side problem
   *                         occurs during processing.
   */
  public String doSASLPlain(ByteSequence bindDN,
                     ByteSequence bindPassword,
                     Map<String,List<String>> saslProperties,
                     List<Control> requestControls,
                     List<Control> responseControls)
         throws ClientException, LDAPException
  {
    String authID  = null;
    String authzID = null;


    // Evaluate the properties provided.  The authID is required, and authzID is
    // optional.
    if (saslProperties == null || saslProperties.isEmpty())
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_NO_SASL_PROPERTIES.get(SASL_MECHANISM_PLAIN);
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }

    for (Entry<String, List<String>> entry : saslProperties.entrySet())
    {
      String name = entry.getKey();
      List<String> values = entry.getValue();
      String lowerName = toLowerCase(name);

      if (lowerName.equals(SASL_PROPERTY_AUTHID))
      {
        authID = getSingleValue(values, ERR_LDAPAUTH_AUTHID_SINGLE_VALUED);
      }
      else if (lowerName.equals(SASL_PROPERTY_AUTHZID))
      {
        authzID = getSingleValue(values, ERR_LDAPAUTH_AUTHZID_SINGLE_VALUED);
      }
      else
      {
        LocalizableMessage message =
            ERR_LDAPAUTH_INVALID_SASL_PROPERTY.get(name, SASL_MECHANISM_PLAIN);
        throw new ClientException(
                ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
      }
    }


    // Make sure that at least the authID was provided.
    if (authID == null || authID.length() == 0)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_SASL_AUTHID_REQUIRED.get(SASL_MECHANISM_PLAIN);
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_PARAM_ERROR, message);
    }


    // Set password to ByteString.empty if the password is null.
    if (bindPassword == null)
    {
        bindPassword = ByteString.empty();
    }

    // Construct the bind request and send it to the server.
    String saslCredentials = (authzID != null ? authzID : "") + '\u0000' + authID + '\u0000' + bindPassword;
    sendBindRequest(SASL_MECHANISM_PLAIN, bindDN, ByteString.valueOfUtf8(saslCredentials), requestControls);

    LDAPMessage responseMessage = readBindResponse(ERR_LDAPAUTH_CANNOT_READ_BIND_RESPONSE);
    responseControls.addAll(responseMessage.getControls());
    checkConnected(responseMessage);
    checkSuccessfulBind(responseMessage, SASL_MECHANISM_PLAIN);
    return null;
  }

  /**
   * Retrieves the set of properties that a client may provide when performing a
   * SASL PLAIN bind, mapped from the property names to their corresponding
   * descriptions.
   *
   * @return  The set of properties that a client may provide when performing a
   *          SASL PLAIN bind, mapped from the property names to their
   *          corresponding descriptions.
   */
  private static LinkedHashMap<String, LocalizableMessage> getSASLPlainProperties()
  {
    LinkedHashMap<String,LocalizableMessage> properties = new LinkedHashMap<>(2);

    properties.put(SASL_PROPERTY_AUTHID,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_AUTHID.get());
    properties.put(SASL_PROPERTY_AUTHZID,
                   INFO_LDAPAUTH_PROPERTY_DESCRIPTION_AUTHZID.get());

    return properties;
  }



  /**
   * Performs a privileged operation under JAAS so that the local authentication
   * information can be available for the SASL bind to the Directory Server.
   *
   * @return  A placeholder object in order to comply with the
   *          <CODE>PrivilegedExceptionAction</CODE> interface.
   *
   * @throws  ClientException  If a client-side problem occurs during the bind
   *                           processing.
   *
   * @throws  LDAPException  If a server-side problem occurs during the bind
   *                         processing.
   */
  @Override
  public Object run() throws ClientException, LDAPException
  {
    if (saslMechanism == null)
    {
      LocalizableMessage message = ERR_LDAPAUTH_NONSASL_RUN_INVOCATION.get(getBacktrace());
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
    }
    else if (saslMechanism.equals(SASL_MECHANISM_GSSAPI))
    {
      doSASLGSSAPI2();
      return null;
    }
    else
    {
      LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_RUN_INVOCATION.get(
          saslMechanism, getBacktrace());
      throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
    }
  }

  private void doSASLGSSAPI2() throws ClientException, LDAPException
  {
    // Create the property map that will be used by the internal SASL handler.
    Map<String, String> saslProperties = new HashMap<>();
    saslProperties.put(Sasl.QOP, gssapiQoP);
    saslProperties.put(Sasl.SERVER_AUTH, "true");


    // Create the SASL client that we will use to actually perform the
    // authentication.
    SaslClient saslClient;
    try
    {
      saslClient =
           Sasl.createSaslClient(new String[] { SASL_MECHANISM_GSSAPI },
                                 gssapiAuthzID, "ldap", hostName,
                                 saslProperties, this);
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_CANNOT_CREATE_SASL_CLIENT.get(
          getExceptionMessage(e));
      throw new ClientException(
              ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }

    // FIXME -- Add controls here?
    ByteString saslCredentials = getSaslCredentialsForInitialBind(saslClient);
    sendBindRequest(SASL_MECHANISM_GSSAPI, gssapiBindDN, saslCredentials, null);

    LDAPMessage responseMessage = readBindResponse(ERR_LDAPAUTH_CANNOT_READ_BIND_RESPONSE);
    // FIXME -- Handle response controls.
    checkConnected(responseMessage);

    while (true)
    {
      BindResponseProtocolOp bindResponse =
           responseMessage.getBindResponseProtocolOp();
      int resultCode = bindResponse.getResultCode();
      if (resultCode == ReturnCode.SUCCESS.get())
      {
        evaluateGSSAPIChallenge(saslClient, bindResponse);
        break;
      }
      else if (resultCode == ReturnCode.SASL_BIND_IN_PROGRESS.get())
      {
        // FIXME -- Add controls here?
        ByteString credBytes = evaluateSaslChallenge(saslClient, bindResponse);
        sendBindRequest(SASL_MECHANISM_GSSAPI, gssapiBindDN, credBytes, null);

        responseMessage = readBindResponse(ERR_LDAPAUTH_CANNOT_READ_BIND_RESPONSE);
        // FIXME -- Handle response controls.
        checkConnected(responseMessage);
      }
      else
      {
        // This is an error.
        LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_BIND_FAILED.get();
        throw new LDAPException(resultCode, bindResponse.getErrorMessage(),
                                message, bindResponse.getMatchedDN(),
                                null);
      }
    }
    // FIXME -- Need to look for things like password expiration warning, reset notice, etc.
  }


  private void evaluateGSSAPIChallenge(SaslClient saslClient, BindResponseProtocolOp bindResponse)
      throws ClientException
  {
    // We should be done after this, but we still need to look for and
    // handle the server SASL credentials.
    ByteString serverSASLCredentials = bindResponse.getServerSASLCredentials();
    if (serverSASLCredentials != null)
    {
      try
      {
        saslClient.evaluateChallenge(serverSASLCredentials.toByteArray());
      }
      catch (Exception e)
      {
        LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_CANNOT_VALIDATE_SERVER_CREDS.get(getExceptionMessage(e));
        throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
      }
    }

    // Just to be sure, check that the login really is complete.
    if (!saslClient.isComplete())
    {
      LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_UNEXPECTED_SUCCESS_RESPONSE.get();
      throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
    }
  }

  private ByteString evaluateSaslChallenge(SaslClient saslClient, BindResponseProtocolOp bindResponse)
      throws ClientException
  {
    try
    {
      ByteString saslCredentials = bindResponse.getServerSASLCredentials();
      byte[] bs = saslCredentials != null ? saslCredentials.toByteArray() : new byte[0];
      return ByteString.wrap(saslClient.evaluateChallenge(bs));
    }
    catch (Exception e)
    {
      LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_CANNOT_VALIDATE_SERVER_CREDS.get(getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
    }
  }

  private ByteString getSaslCredentialsForInitialBind(SaslClient saslClient) throws ClientException
  {
    if (saslClient.hasInitialResponse())
    {
      try
      {
        return ByteString.wrap(saslClient.evaluateChallenge(new byte[0]));
      }
      catch (Exception e)
      {
        LocalizableMessage message = ERR_LDAPAUTH_GSSAPI_CANNOT_CREATE_INITIAL_CHALLENGE.get(getExceptionMessage(e));
        throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message, e);
      }
    }
    return null;
  }

  /**
   * Look at the protocol op from the response.
   * If it's a bind response, then continue.
   * If it's an extended response, then check it is not a notice of disconnection.
   * Otherwise, generate an error.
   */
  private void checkConnected(LDAPMessage responseMessage) throws LDAPException, ClientException
  {
    switch (responseMessage.getProtocolOpType())
    {
      case OP_TYPE_BIND_RESPONSE:
        // We'll deal with this later.
        break;

      case OP_TYPE_EXTENDED_RESPONSE:
        ExtendedResponseProtocolOp extendedResponse =
             responseMessage.getExtendedResponseProtocolOp();
        String responseOID = extendedResponse.getOID();
        if (OID_NOTICE_OF_DISCONNECTION.equals(responseOID))
        {
          LocalizableMessage message = ERR_LDAPAUTH_SERVER_DISCONNECT.
              get(extendedResponse.getResultCode(), extendedResponse.getErrorMessage());
          throw new LDAPException(extendedResponse.getResultCode(), message);
        }
        else
        {
          LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_EXTENDED_RESPONSE.get(extendedResponse);
          throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
        }

      default:
        LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_RESPONSE.get(responseMessage.getProtocolOp());
        throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
    }
  }

  /**
   * Handles the authentication callbacks to provide information needed by the
   * JAAS login process.
   *
   * @param  callbacks  The callbacks needed to provide information for the JAAS
   *                    login process.
   *
   * @throws  UnsupportedCallbackException  If an unexpected callback is
   *                                        included in the provided set.
   */
  @Override
  public void handle(Callback[] callbacks)
         throws UnsupportedCallbackException
  {
    if (saslMechanism ==  null)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_NONSASL_CALLBACK_INVOCATION.get(getBacktrace());
      throw new UnsupportedCallbackException(callbacks[0], message.toString());
    }
    else if (saslMechanism.equals(SASL_MECHANISM_GSSAPI))
    {
      for (Callback cb : callbacks)
      {
        if (cb instanceof NameCallback)
        {
          ((NameCallback) cb).setName(gssapiAuthID);
        }
        else if (cb instanceof PasswordCallback)
        {
          if (gssapiAuthPW == null)
          {
            System.out.print(INFO_LDAPAUTH_PASSWORD_PROMPT.get(gssapiAuthID));
            try
            {
              gssapiAuthPW = ConsoleApplication.readPassword();
            }
            catch (ClientException e)
            {
              throw new UnsupportedCallbackException(cb, e.getLocalizedMessage());
            }
          }

          ((PasswordCallback) cb).setPassword(gssapiAuthPW);
        }
        else
        {
          LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_GSSAPI_CALLBACK.get(cb);
          throw new UnsupportedCallbackException(cb, message.toString());
        }
      }
    }
    else
    {
      LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_CALLBACK_INVOCATION.get(
          saslMechanism, getBacktrace());
      throw new UnsupportedCallbackException(callbacks[0], message.toString());
    }
  }



  /**
   * Uses the "Who Am I?" extended operation to request that the server provide
   * the client with the authorization identity for this connection.
   *
   * @return  An ASN.1 octet string containing the authorization identity, or
   *          <CODE>null</CODE> if the client is not authenticated or is
   *          authenticated anonymously.
   *
   * @throws  ClientException  If a client-side problem occurs during the
   *                           request processing.
   *
   * @throws  LDAPException  If a server-side problem occurs during the request
   *                         processing.
   */
  public ByteString requestAuthorizationIdentity()
         throws ClientException, LDAPException
  {
    // Construct the extended request and send it to the server.
    ExtendedRequestProtocolOp extendedRequest =
         new ExtendedRequestProtocolOp(OID_WHO_AM_I_REQUEST);
    LDAPMessage requestMessage =
         new LDAPMessage(nextMessageID.getAndIncrement(), extendedRequest);

    try
    {
      writer.writeMessage(requestMessage);
    }
    catch (IOException ioe)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_CANNOT_SEND_WHOAMI_REQUEST.get(getExceptionMessage(ioe));
      throw new ClientException(ReturnCode.CLIENT_SIDE_SERVER_DOWN,
              message, ioe);
    }
    catch (Exception e)
    {
      LocalizableMessage message =
          ERR_LDAPAUTH_CANNOT_SEND_WHOAMI_REQUEST.get(getExceptionMessage(e));
      throw new ClientException(ReturnCode.CLIENT_SIDE_ENCODING_ERROR,
                                message, e);
    }


    LDAPMessage responseMessage = readBindResponse(ERR_LDAPAUTH_CANNOT_READ_WHOAMI_RESPONSE);

    // If the protocol op isn't an extended response, then that's a problem.
    if (responseMessage.getProtocolOpType() != OP_TYPE_EXTENDED_RESPONSE)
    {
      LocalizableMessage message = ERR_LDAPAUTH_UNEXPECTED_RESPONSE.get(responseMessage.getProtocolOp());
      throw new ClientException(ReturnCode.CLIENT_SIDE_LOCAL_ERROR, message);
    }


    // Get the extended response and see if it has the "notice of disconnection"
    // OID.  If so, then the server is closing the connection.
    ExtendedResponseProtocolOp extendedResponse =
         responseMessage.getExtendedResponseProtocolOp();
    String responseOID = extendedResponse.getOID();
    if (OID_NOTICE_OF_DISCONNECTION.equals(responseOID))
    {
      LocalizableMessage message = ERR_LDAPAUTH_SERVER_DISCONNECT.get(
          extendedResponse.getResultCode(), extendedResponse.getErrorMessage());
      throw new LDAPException(extendedResponse.getResultCode(), message);
    }


    // It isn't a notice of disconnection so it must be the "Who Am I?"
    // response and the value would be the authorization ID.  However, first
    // check that it was successful.  If it was not, then fail.
    int resultCode = extendedResponse.getResultCode();
    if (resultCode != ReturnCode.SUCCESS.get())
    {
      LocalizableMessage message = ERR_LDAPAUTH_WHOAMI_FAILED.get();
      throw new LDAPException(resultCode, extendedResponse.getErrorMessage(),
                              message, extendedResponse.getMatchedDN(),
                              null);
    }


    // Get the authorization ID (if there is one) and return it to the caller.
    ByteString authzID = extendedResponse.getValue();
    if (authzID == null || authzID.length() == 0)
    {
      return null;
    }

    if (!"dn:".equalsIgnoreCase(authzID.toString()))
    {
      return authzID;
    }
    return null;
  }
}
