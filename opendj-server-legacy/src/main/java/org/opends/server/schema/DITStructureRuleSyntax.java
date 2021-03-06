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
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.server.schema;
import static org.opends.messages.SchemaMessages.*;
import static org.opends.server.schema.SchemaConstants.*;
import static org.opends.server.util.StaticUtils.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.ldap.ByteSequence;
import org.forgerock.opendj.ldap.ResultCode;
import org.forgerock.opendj.ldap.schema.Syntax;
import org.forgerock.opendj.server.config.server.AttributeSyntaxCfg;
import org.opends.server.api.AttributeSyntax;
import org.opends.server.core.DirectoryServer;
import org.opends.server.types.DITStructureRule;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.NameForm;
import org.opends.server.types.Schema;

/**
 * This class implements the DIT structure rule description syntax, which is
 * used to hold DIT structure rule definitions in the server schema.  The format
 * of this syntax is defined in RFC 2252.
 */
public class DITStructureRuleSyntax
       extends AttributeSyntax<AttributeSyntaxCfg>
{

  /**
   * Creates a new instance of this syntax.  Note that the only thing that
   * should be done here is to invoke the default constructor for the
   * superclass.  All initialization should be performed in the
   * <CODE>initializeSyntax</CODE> method.
   */
  public DITStructureRuleSyntax()
  {
    super();
  }

  /** {@inheritDoc} */
  @Override
  public Syntax getSDKSyntax(org.forgerock.opendj.ldap.schema.Schema schema)
  {
    return schema.getSyntax(SchemaConstants.SYNTAX_DIT_STRUCTURE_RULE_OID);
  }

  /** {@inheritDoc} */
  @Override
  public String getName()
  {
    return SYNTAX_DIT_STRUCTURE_RULE_NAME;
  }

  /** {@inheritDoc} */
  @Override
  public String getOID()
  {
    return SYNTAX_DIT_STRUCTURE_RULE_OID;
  }

  /** {@inheritDoc} */
  @Override
  public String getDescription()
  {
    return SYNTAX_DIT_STRUCTURE_RULE_DESCRIPTION;
  }

  /**
   * Decodes the contents of the provided ASN.1 octet string as a DIT structure
   * rule definition according to the rules of this syntax.  Note that the
   * provided octet string value does not need to be normalized (and in fact, it
   * should not be in order to allow the desired capitalization to be
   * preserved).
   *
   * @param  value                 The ASN.1 octet string containing the value
   *                               to decode (it does not need to be
   *                               normalized).
   * @param  schema                The schema to use to resolve references to
   *                               other schema elements.
   * @param  allowUnknownElements  Indicates whether to allow values that
   *                               reference a name form and/or superior rules
   *                               which are not defined in the server schema.
   *                               This should only be true when called by
   *                               {@code valueIsAcceptable}.
   *
   * @return  The decoded DIT structure rule definition.
   *
   * @throws  DirectoryException  If the provided value cannot be decoded as an
   *                              DIT structure rule definition.
   */
  public static DITStructureRule decodeDITStructureRule(ByteSequence value,
                                      Schema schema,
                                      boolean allowUnknownElements)
         throws DirectoryException
  {
    // Get string representations of the provided value using the provided form
    // and with all lowercase characters.
    String valueStr = value.toString();
    String lowerStr = toLowerCase(valueStr);


    // We'll do this a character at a time.  First, skip over any leading
    // whitespace.
    int pos    = 0;
    int length = valueStr.length();
    while (pos < length && valueStr.charAt(pos) == ' ')
    {
      pos++;
    }

    if (pos >= length)
    {
      // This means that the value was empty or contained only whitespace.  That
      // is illegal.
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_EMPTY_VALUE.get();
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // The next character must be an open parenthesis.  If it is not, then that
    // is an error.
    char c = valueStr.charAt(pos++);
    if (c != '(')
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_EXPECTED_OPEN_PARENTHESIS.get(
          valueStr, pos-1, c);
      throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Skip over any spaces immediately following the opening parenthesis.
    while (pos < length && ((c = valueStr.charAt(pos)) == ' '))
    {
      pos++;
    }

    if (pos >= length)
    {
      // This means that the end of the value was reached before we could find
      // the OID.  Ths is illegal.
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // The next set of characters must be the rule ID, which is an integer.
    int ruleIDStartPos = pos;
    while (pos < length && ((c = valueStr.charAt(pos++)) != ' '))
    {
      if (! isDigit(c))
      {
        LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR_IN_RULE_ID.get(
            valueStr, c, pos-1);
        throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                     message);
      }
    }

    // If we're at the end of the value, then it isn't a valid DIT structure
    // rule description.  Otherwise, parse out the rule ID.
    int ruleID;
    if (pos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }
    else
    {
      ruleID = Integer.parseInt(valueStr.substring(ruleIDStartPos, pos-1));
    }


    // Skip over the space(s) after the rule ID.
    while (pos < length && ((c = valueStr.charAt(pos)) == ' '))
    {
      pos++;
    }

    if (pos >= length)
    {
      // This means that the end of the value was reached before we could find
      // the rule ID.  Ths is illegal.
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // At this point, we should have a pretty specific syntax that describes
    // what may come next, but some of the components are optional and it would
    // be pretty easy to put something in the wrong order, so we will be very
    // flexible about what we can accept.  Just look at the next token, figure
    // out what it is and how to treat what comes after it, then repeat until
    // we get to the end of the value.  But before we start, set default values
    // for everything else we might need to know.
    LinkedHashMap<String,String> names = new LinkedHashMap<>();
    String description = null;
    boolean isObsolete = false;
    NameForm nameForm = null;
    boolean nameFormGiven = false;
    LinkedHashSet<DITStructureRule> superiorRules = null;
    LinkedHashMap<String,List<String>> extraProperties = new LinkedHashMap<>();


    while (true)
    {
      StringBuilder tokenNameBuffer = new StringBuilder();
      pos = readTokenName(valueStr, tokenNameBuffer, pos);
      String tokenName = tokenNameBuffer.toString();
      String lowerTokenName = toLowerCase(tokenName);
      if (tokenName.equals(")"))
      {
        // We must be at the end of the value.  If not, then that's a problem.
        if (pos < length)
        {
          LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_UNEXPECTED_CLOSE_PARENTHESIS.
              get(valueStr, pos-1);
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                       message);
        }

        break;
      }
      else if (lowerTokenName.equals("name"))
      {
        // This specifies the set of names for the DIT structure rule.  It may
        // be a single name in single quotes, or it may be an open parenthesis
        // followed by one or more names in single quotes separated by spaces.
        c = valueStr.charAt(pos++);
        if (c == '\'')
        {
          StringBuilder userBuffer  = new StringBuilder();
          StringBuilder lowerBuffer = new StringBuilder();
          pos = readQuotedString(valueStr, lowerStr, userBuffer, lowerBuffer, pos-1);
          names.put(lowerBuffer.toString(), userBuffer.toString());
        }
        else if (c == '(')
        {
          StringBuilder userBuffer  = new StringBuilder();
          StringBuilder lowerBuffer = new StringBuilder();
          pos = readQuotedString(valueStr, lowerStr, userBuffer, lowerBuffer,
                                 pos);
          names.put(lowerBuffer.toString(), userBuffer.toString());


          while (true)
          {
            if (valueStr.charAt(pos) == ')')
            {
              // Skip over any spaces after the parenthesis.
              pos++;
              while (pos < length && ((c = valueStr.charAt(pos)) == ' '))
              {
                pos++;
              }

              break;
            }
            else
            {
              userBuffer  = new StringBuilder();
              lowerBuffer = new StringBuilder();

              pos = readQuotedString(valueStr, lowerStr, userBuffer,
                                     lowerBuffer, pos);
              names.put(lowerBuffer.toString(), userBuffer.toString());
            }
          }
        }
        else
        {
          // This is an illegal character.
          LocalizableMessage message =
              ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR.get(valueStr, c, pos-1);
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
        }
      }
      else if (lowerTokenName.equals("desc"))
      {
        // This specifies the description for the DIT structure rule.  It is an
        // arbitrary string of characters enclosed in single quotes.
        StringBuilder descriptionBuffer = new StringBuilder();
        pos = readQuotedString(valueStr, descriptionBuffer, pos);
        description = descriptionBuffer.toString();
      }
      else if (lowerTokenName.equals("obsolete"))
      {
        // This indicates whether the DIT structure rule should be considered
        // obsolete.  We do not need to do any more parsing for this token.
        isObsolete = true;
      }
      else if (lowerTokenName.equals("form"))
      {
        // This should be the OID of the associated name form.
        StringBuilder woidBuffer = new StringBuilder();
        pos = readWOID(lowerStr, woidBuffer, pos);

        nameFormGiven = true;
        nameForm = schema.getNameForm(woidBuffer.toString());
        if (nameForm == null && ! allowUnknownElements)
        {
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
              ERR_ATTR_SYNTAX_DSR_UNKNOWN_NAME_FORM.get(valueStr, woidBuffer));
        }
      }
      else if (lowerTokenName.equals("sup"))
      {
        LinkedList<DITStructureRule> superiorList = new LinkedList<>();

        // This specifies the set of superior rule IDs (which are integers) for
        // this DIT structure rule.  It may be a single rule ID or a set of
        // rule IDs enclosed in parentheses and separated by spaces.
        c = valueStr.charAt(pos++);
        if (c == '(')
        {
          while (true)
          {
            // Skip over any leading spaces.
            while (pos < length && ((c = valueStr.charAt(pos)) == ' '))
            {
              pos++;
            }

            if (pos >= length)
            {
              LocalizableMessage message =
                  ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
              throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                           message);
            }

            // Read the next integer value.
            ruleIDStartPos = pos;
            while (pos < length && ((c = valueStr.charAt(pos++)) != ' '))
            {
              if (! isDigit(c))
              {
                LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR_IN_RULE_ID.
                    get(valueStr, c, pos-1);
                throw new DirectoryException(
                               ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
              }
            }

            // If we're at the end of the value, then it isn't a valid DIT
            // structure rule description.  Otherwise, parse out the rule ID.
            int supRuleID;
            if (pos >= length)
            {
              LocalizableMessage message =
                  ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
              throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                           message);
            }
            else
            {
              supRuleID =
                   Integer.parseInt(valueStr.substring(ruleIDStartPos, pos-1));
            }


            // Get the DIT structure rule with the specified rule ID.
            DITStructureRule superiorRule =
                 schema.getDITStructureRule(supRuleID);
            if (superiorRule == null)
            {
              if (! allowUnknownElements)
              {
                LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_UNKNOWN_RULE_ID.get(
                    valueStr, supRuleID);
                throw new DirectoryException(
                               ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
              }
            }
            else
            {
              superiorList.add(superiorRule);
            }


            // Skip over any trailing spaces.
            while (pos < length && ((c = valueStr.charAt(pos)) == ' '))
            {
              pos++;
            }

            if (pos >= length)
            {
              LocalizableMessage message =
                  ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
              throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                           message);
            }


            // If the next character is a closing parenthesis, then read any
            // spaces after it and break out of the loop.
            if (c == ')')
            {
              pos++;
              while (pos < length && ((c = valueStr.charAt(pos)) == ' '))
              {
                pos++;
              }

              if (pos >= length)
              {
                LocalizableMessage message =
                    ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
                throw new DirectoryException(
                               ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
              }

              break;
            }
          }
        }
        else
        {
          if (pos >= length)
          {
            LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
            throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                         message);
          }

          // Read the next integer value.
          ruleIDStartPos = pos - 1;
          while (pos < length && ((c = valueStr.charAt(pos++)) != ' '))
          {
            if (! isDigit(c))
            {
              LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR_IN_RULE_ID.get(valueStr, c, pos-1);
              throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                           message);
            }
          }

          // If we're at the end of the value, then it isn't a valid DIT
          // structure rule description.  Otherwise, parse out the rule ID.
          int supRuleID;
          if (pos >= length)
          {
            LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
            throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                         message);
          }
          else
          {
            supRuleID =
                 Integer.parseInt(valueStr.substring(ruleIDStartPos, pos-1));
          }


          // Get the DIT structure rule with the specified rule ID.
          DITStructureRule superiorRule = schema.getDITStructureRule(supRuleID);
          if (superiorRule == null)
          {
            if (! allowUnknownElements)
            {
              LocalizableMessage message =
                  ERR_ATTR_SYNTAX_DSR_UNKNOWN_RULE_ID.get(valueStr, supRuleID);
              throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                           message);
            }
          }
          else
          {
            superiorList.add(superiorRule);
          }


          // Skip over any trailing spaces.
          while (pos < length && ((c = valueStr.charAt(pos)) == ' '))
          {
            pos++;
          }

          if (pos >= length)
          {
            LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
            throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                         message);
          }
        }

        superiorRules = new LinkedHashSet<>(superiorList);
      }
      else
      {
        // This must be a non-standard property and it must be followed by
        // either a single value in single quotes or an open parenthesis
        // followed by one or more values in single quotes separated by spaces
        // followed by a close parenthesis.
        LinkedList<String> valueList = new LinkedList<>();
        pos = readExtraParameterValues(valueStr, valueList, pos);
        extraProperties.put(tokenName, valueList);
      }
    }


    if (nameForm == null && !nameFormGiven)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_NO_NAME_FORM.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    return new DITStructureRule(value.toString(), names, ruleID, description,
                                isObsolete, nameForm, superiorRules,
                                extraProperties);
  }

  /**
   * Reads the next token name from the DIT content rule definition, skipping
   * over any leading or trailing spaces, and appends it to the provided buffer.
   *
   * @param  valueStr   The string representation of the DIT content rule
   *                    definition.
   * @param  tokenName  The buffer into which the token name will be written.
   * @param  startPos   The position in the provided string at which to start
   *                    reading the token name.
   *
   * @return  The position of the first character that is not part of the token
   *          name or one of the trailing spaces after it.
   *
   * @throws  DirectoryException  If a problem is encountered while reading the
   *                              token name.
   */
  private static int readTokenName(String valueStr, StringBuilder tokenName,
                                   int startPos)
          throws DirectoryException
  {
    // Skip over any spaces at the beginning of the value.
    char c = '\u0000';
    int  length = valueStr.length();
    while (startPos < length && ((c = valueStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }

    if (startPos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Read until we find the next space.
    while (startPos < length && ((c = valueStr.charAt(startPos++)) != ' '))
    {
      tokenName.append(c);
    }


    // Skip over any trailing spaces after the value.
    while (startPos < length && ((c = valueStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }


    // Return the position of the first non-space character after the token.
    return startPos;
  }

  /**
   * Reads the value of a string enclosed in single quotes, skipping over the
   * quotes and any leading or trailing spaces, and appending the string to the
   * provided buffer.
   *
   * @param  valueStr     The user-provided representation of the DIT content
   *                      rule definition.
   * @param  valueBuffer  The buffer into which the user-provided representation
   *                      of the value will be placed.
   * @param  startPos     The position in the provided string at which to start
   *                      reading the quoted string.
   *
   * @return  The position of the first character that is not part of the quoted
   *          string or one of the trailing spaces after it.
   *
   * @throws  DirectoryException  If a problem is encountered while reading the
   *                              quoted string.
   */
  private static int readQuotedString(String valueStr,
                                      StringBuilder valueBuffer, int startPos)
          throws DirectoryException
  {
    // Skip over any spaces at the beginning of the value.
    char c = '\u0000';
    int  length = valueStr.length();
    while (startPos < length && ((c = valueStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }

    if (startPos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // The next character must be a single quote.
    if (c != '\'')
    {
      LocalizableMessage message =
          ERR_ATTR_SYNTAX_DSR_EXPECTED_QUOTE_AT_POS.get(valueStr, startPos, c);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Read until we find the closing quote.
    startPos++;
    while (startPos < length && ((c = valueStr.charAt(startPos)) != '\''))
    {
      valueBuffer.append(c);
      startPos++;
    }


    // Skip over any trailing spaces after the value.
    startPos++;
    while (startPos < length && ((c = valueStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }


    // If we're at the end of the value, then that's illegal.
    if (startPos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Return the position of the first non-space character after the token.
    return startPos;
  }

  /**
   * Reads the value of a string enclosed in single quotes, skipping over the
   * quotes and any leading or trailing spaces, and appending the string to the
   * provided buffer.
   *
   * @param  valueStr     The user-provided representation of the DIT content
   *                      rule definition.
   * @param  lowerStr     The all-lowercase representation of the DIT content
   *                      rule definition.
   * @param  userBuffer   The buffer into which the user-provided representation
   *                      of the value will be placed.
   * @param  lowerBuffer  The buffer into which the all-lowercase representation
   *                      of the value will be placed.
   * @param  startPos     The position in the provided string at which to start
   *                      reading the quoted string.
   *
   * @return  The position of the first character that is not part of the quoted
   *          string or one of the trailing spaces after it.
   *
   * @throws  DirectoryException  If a problem is encountered while reading the
   *                              quoted string.
   */
  private static int readQuotedString(String valueStr, String lowerStr,
                                      StringBuilder userBuffer,
                                      StringBuilder lowerBuffer, int startPos)
          throws DirectoryException
  {
    // Skip over any spaces at the beginning of the value.
    char c = '\u0000';
    int  length = lowerStr.length();
    while (startPos < length && ((c = lowerStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }

    if (startPos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // The next character must be a single quote.
    if (c != '\'')
    {
      LocalizableMessage message =
          ERR_ATTR_SYNTAX_DSR_EXPECTED_QUOTE_AT_POS.get(valueStr, startPos, c);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Read until we find the closing quote.
    startPos++;
    while (startPos < length && ((c = lowerStr.charAt(startPos)) != '\''))
    {
      lowerBuffer.append(c);
      userBuffer.append(valueStr.charAt(startPos));
      startPos++;
    }


    // Skip over any trailing spaces after the value.
    startPos++;
    while (startPos < length && ((c = lowerStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }


    // If we're at the end of the value, then that's illegal.
    if (startPos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Return the position of the first non-space character after the token.
    return startPos;
  }

  /**
   * Reads an attributeType/objectclass description or numeric OID from the
   * provided string, skipping over any leading or trailing spaces, and
   * appending the value to the provided buffer.
   *
   * @param  lowerStr    The string from which the name or OID is to be read.
   * @param  woidBuffer  The buffer into which the name or OID should be
   *                     appended.
   * @param  startPos    The position at which to start reading.
   *
   * @return  The position of the first character after the name or OID that is
   *          not a space.
   *
   * @throws  DirectoryException  If a problem is encountered while reading the
   *                              name or OID.
   */
  private static int readWOID(String lowerStr, StringBuilder woidBuffer,
                              int startPos)
          throws DirectoryException
  {
    // Skip over any spaces at the beginning of the value.
    char c = '\u0000';
    int  length = lowerStr.length();
    while (startPos < length && ((c = lowerStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }

    if (startPos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // The next character must be either numeric (for an OID) or alphabetic (for
    // an attribute type/objectclass description).
    if (isDigit(c))
    {
      // This must be a numeric OID.  In that case, we will accept only digits
      // and periods, but not consecutive periods.
      boolean lastWasPeriod = false;
      while (startPos < length && ((c = lowerStr.charAt(startPos++)) != ' '))
      {
        if (c == '.')
        {
          if (lastWasPeriod)
          {
            LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_DOUBLE_PERIOD_IN_NUMERIC_OID.
                get(lowerStr, startPos-1);
            throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                         message);
          }
          else
          {
            woidBuffer.append(c);
            lastWasPeriod = true;
          }
        }
        else if (! isDigit(c))
        {
          // Technically, this must be an illegal character.  However, it is
          // possible that someone just got sloppy and did not include a space
          // between the name/OID and a closing parenthesis.  In that case,
          // we'll assume it's the end of the value.  What's more, we'll have
          // to prematurely return to nasty side effects from stripping off
          // additional characters.
          if (c == ')')
          {
            return startPos-1;
          }

          // This must have been an illegal character.
          LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR_IN_NUMERIC_OID.get(lowerStr, c, startPos-1);
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
        }
        else
        {
          woidBuffer.append(c);
          lastWasPeriod = false;
        }
      }
    }
    else if (isAlpha(c))
    {
      // This must be an attribute type/objectclass description.  In this case,
      // we will only accept alphabetic characters, numeric digits, and the
      // hyphen.
      while (startPos < length && ((c = lowerStr.charAt(startPos++)) != ' '))
      {
        if (isAlpha(c) || isDigit(c) || c == '-' ||
            (c == '_' && DirectoryServer.allowAttributeNameExceptions()))
        {
          woidBuffer.append(c);
        }
        else
        {
          // Technically, this must be an illegal character.  However, it is
          // possible that someone just got sloppy and did not include a space
          // between the name/OID and a closing parenthesis.  In that case,
          // we'll assume it's the end of the value.  What's more, we'll have
          // to prematurely return to nasty side effects from stripping off
          // additional characters.
          if (c == ')')
          {
            return startPos-1;
          }

          // This must have been an illegal character.
          LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR_IN_STRING_OID.get(lowerStr, c, startPos-1);
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
        }
      }
    }
    else
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR.get(lowerStr, c, startPos);
      throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Skip over any trailing spaces after the value.
    while (startPos < length && ((c = lowerStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }


    // If we're at the end of the value, then that's illegal.
    if (startPos >= length)
    {
      LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(lowerStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Return the position of the first non-space character after the token.
    return startPos;
  }

  /**
   * Reads the value for an "extra" parameter.  It will handle a single unquoted
   * word (which is technically illegal, but we'll allow it), a single quoted
   * string, or an open parenthesis followed by a space-delimited set of quoted
   * strings or unquoted words followed by a close parenthesis.
   *
   * @param  valueStr   The string containing the information to be read.
   * @param  valueList  The list of "extra" parameter values read so far.
   * @param  startPos   The position in the value string at which to start
   *                    reading.
   *
   * @return  The "extra" parameter value that was read.
   *
   * @throws  DirectoryException  If a problem occurs while attempting to read
   *                              the value.
   */
  private static int readExtraParameterValues(String valueStr,
                          List<String> valueList, int startPos)
          throws DirectoryException
  {
    // Skip over any leading spaces.
    int length = valueStr.length();
    char c = '\u0000';
    while (startPos < length && ((c = valueStr.charAt(startPos)) == ' '))
    {
      startPos++;
    }

    if (startPos >= length)
    {
      LocalizableMessage message =
          ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }


    // Look at the next character.  If it is a quote, then parse until the next
    // quote and end.  If it is an open parenthesis, then parse individual
    // values until the close parenthesis and end.  Otherwise, parse until the
    // next space and end.
    if (c == '\'')
    {
      // Parse until the closing quote.
      StringBuilder valueBuffer = new StringBuilder();
      startPos++;
      while (startPos < length && ((c = valueStr.charAt(startPos)) != '\''))
      {
        valueBuffer.append(c);
        startPos++;
      }
      startPos++;
      valueList.add(valueBuffer.toString());
    }
    else if (c == '(')
    {
      startPos++;
      // We're expecting a list of values. Quoted, space separated.
      while (true)
      {
        // Skip over any leading spaces;
        while (startPos < length && ((c = valueStr.charAt(startPos)) == ' '))
        {
          startPos++;
        }

        if (startPos >= length)
        {
          LocalizableMessage message =
              ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX,
                                       message);
        }

        if (c == ')')
        {
          // This is the end of the list.
          startPos++;
          break;
        }
        else if (c == '(')
        {
          // This is an illegal character.
          LocalizableMessage message = ERR_ATTR_SYNTAX_DSR_ILLEGAL_CHAR.get(valueStr, c, startPos);
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
        }
        else if (c == '\'')
        {
          // We have a quoted string
          StringBuilder valueBuffer = new StringBuilder();
          startPos++;
          while (startPos < length && ((c = valueStr.charAt(startPos)) != '\''))
          {
            valueBuffer.append(c);
            startPos++;
          }

          valueList.add(valueBuffer.toString());
          startPos++;
        }
        else
        {
          //Consider unquoted string
          StringBuilder valueBuffer = new StringBuilder();
          while (startPos < length && ((c = valueStr.charAt(startPos)) != ' '))
          {
            valueBuffer.append(c);
            startPos++;
          }

          valueList.add(valueBuffer.toString());
        }

        if (startPos >= length)
        {
          LocalizableMessage message =
              ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
          throw new DirectoryException(ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
        }
      }
    }
    else
    {
      // Parse until the next space.
      StringBuilder valueBuffer = new StringBuilder();
      while (startPos < length && ((c = valueStr.charAt(startPos)) != ' '))
      {
        valueBuffer.append(c);
        startPos++;
      }

      valueList.add(valueBuffer.toString());
    }

    // Skip over any trailing spaces.
    while (startPos < length && valueStr.charAt(startPos) == ' ')
    {
      startPos++;
    }

    if (startPos >= length)
    {
      LocalizableMessage message =
          ERR_ATTR_SYNTAX_DSR_TRUNCATED_VALUE.get(valueStr);
      throw new DirectoryException(
              ResultCode.INVALID_ATTRIBUTE_SYNTAX, message);
    }

    return startPos;
  }
}

