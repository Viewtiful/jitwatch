/*
 * Copyright (c) 2013, 2014 Chris Newland.
 * Licensed under https://github.com/AdoptOpenJDK/jitwatch/blob/master/LICENSE-BSD
 * Instructions: https://github.com/AdoptOpenJDK/jitwatch/wiki
 */
package org.adoptopenjdk.jitwatch.suggestion;

import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ALWAYS;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_BCI;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_BRANCH_COUNT;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_BRANCH_PROB;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_BYTES;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_ID;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_IICOUNT;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_METHOD;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.ATTR_REASON;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.C_NEWLINE;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.NEVER;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_BC;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_BRANCH;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_CALL;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_INLINE_FAIL;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_METHOD;
import static org.adoptopenjdk.jitwatch.core.JITWatchConstants.TAG_PARSE;

import java.util.HashMap;
import java.util.Map;

import org.adoptopenjdk.jitwatch.journal.ILastTaskParseTagVisitable;
import org.adoptopenjdk.jitwatch.journal.JournalUtil;
import org.adoptopenjdk.jitwatch.model.IMetaMember;
import org.adoptopenjdk.jitwatch.model.IParseDictionary;
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel;
import org.adoptopenjdk.jitwatch.model.LogParseException;
import org.adoptopenjdk.jitwatch.model.Tag;
import org.adoptopenjdk.jitwatch.suggestion.Suggestion.SuggestionType;
import org.adoptopenjdk.jitwatch.util.ParseUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AttributeSuggestionWalker extends AbstractSuggestionVisitable implements ILastTaskParseTagVisitable
{
	private IMetaMember metaMember;

	private static final Map<String, Double> scoreMap = new HashMap<>();
	private static final Map<String, String> explanationMap = new HashMap<>();

	// see
	// https://wikis.oracle.com/display/HotSpotInternals/Server+Compiler+Inlining+Messages

	// TODO update for Java8
	private static final String REASON_HOT_METHOD_TOO_BIG = "hot method too big";
	private static final String REASON_TOO_BIG = "too big";
	private static final String REASON_ALREADY_COMPILED_INTO_A_BIG_METHOD = "already compiled into a big method";
	private static final String REASON_ALREADY_COMPILED_INTO_A_MEDIUM_METHOD = "already compiled into a medium method";
	private static final String REASON_NEVER_EXECUTED = "never executed";
	private static final String REASON_EXEC_LESS_MIN_INLINING_THRESHOLD = "executed < MinInliningThreshold times";
	private static final String REASON_CALL_SITE_NOT_REACHED = "call site not reached";
	private static final String REASON_UNCERTAIN_BRANCH = "Uncertain branch";
	private static final String REASON_NATIVE_METHOD = "native method";
	
	private static final String REASON_CALLEE_IS_TOO_LARGE = "callee is too large";
	private static final String REASON_NO_STATIC_BINDING = "no static binding";

	static
	{
		scoreMap.put(REASON_HOT_METHOD_TOO_BIG, 1.0);
		scoreMap.put(REASON_CALLEE_IS_TOO_LARGE, 0.5);
		scoreMap.put(REASON_UNCERTAIN_BRANCH, 0.5);
		scoreMap.put(REASON_TOO_BIG, 0.5);
		scoreMap.put(REASON_ALREADY_COMPILED_INTO_A_BIG_METHOD, 0.4);
		scoreMap.put(REASON_ALREADY_COMPILED_INTO_A_MEDIUM_METHOD, 0.4);
		scoreMap.put(REASON_EXEC_LESS_MIN_INLINING_THRESHOLD, 0.2);
		scoreMap.put(REASON_NO_STATIC_BINDING, 0.2);

		scoreMap.put(REASON_NEVER_EXECUTED, 0.0);
		scoreMap.put(REASON_NATIVE_METHOD, 0.0);

		scoreMap.put(REASON_CALL_SITE_NOT_REACHED, 0.0);

		explanationMap
				.put(REASON_HOT_METHOD_TOO_BIG,
						"The callee method is 'hot' but is too big to be inlined into the caller.\nYou may want to consider refactoring the callee into smaller methods.");
		explanationMap.put(REASON_TOO_BIG, "The callee method is not 'hot' but is too big to be inlined into the caller method.");
		explanationMap.put(REASON_ALREADY_COMPILED_INTO_A_BIG_METHOD,
				"The callee method is not 'hot' but is too big to be inlined into the caller method.");
		explanationMap.put(REASON_EXEC_LESS_MIN_INLINING_THRESHOLD, "The callee method was not called enough times to be inlined.");
	
		explanationMap
		.put(REASON_CALLEE_IS_TOO_LARGE,
				"The callee method is greater than the max inlining size at the C1 compiler level.");

		explanationMap
		.put(REASON_NO_STATIC_BINDING,
				"The callee is known but there is no static binding so could not be inlined.");
	}

	private static final int MIN_BRANCH_INVOCATIONS = 1000;
	private static final int MIN_INLINING_INVOCATIONS = 1000;
	private static final Logger logger = LoggerFactory.getLogger(AttributeSuggestionWalker.class);

	public AttributeSuggestionWalker(IReadOnlyJITDataModel model)
	{
		super(model);
	}

	@Override
	public void visit(IMetaMember metaMember)
	{
		if (metaMember.isCompiled())
		{
			this.metaMember = metaMember;

			try
			{
				JournalUtil.visitParseTagsOfLastTask(metaMember, this);
			}
			catch (LogParseException e)
			{
				logger.error("Error building suggestions", e);
			}
		}
	}

	private void processParseTag(Tag parseTag, IMetaMember caller, IParseDictionary parseDictionary)
	{
		String methodID = null;

		int currentBytecode = -1;

		for (Tag child : parseTag.getChildren())
		{
			String tagName = child.getName();
			Map<String, String> attrs = child.getAttrs();

			switch (tagName)
			{
			case TAG_METHOD:
			{
				methodID = attrs.get(ATTR_ID);
			}
				break;
			case TAG_BC:
			{
				String bci = attrs.get(ATTR_BCI);
				currentBytecode = Integer.parseInt(bci);
			}
				break;
			case TAG_BRANCH:
				handleBranchTag(attrs, currentBytecode, caller);
				break;

			case TAG_CALL:
			{
				methodID = attrs.get(ATTR_METHOD);
			}
				break;

			case TAG_INLINE_FAIL:
				handleInlineFailTag(attrs, methodID, caller, currentBytecode, parseDictionary);
				break;

			case TAG_PARSE:
			{
				String callerID = attrs.get(ATTR_METHOD);
				IMetaMember nestedCaller = ParseUtil.lookupMember(callerID, parseDictionary, model);
				processParseTag(child, nestedCaller, parseDictionary);
			}

			default:
				break;
			}
		}
	}

	private void handleInlineFailTag(Map<String, String> attrs, String methodID, IMetaMember caller, int currentBytecode, IParseDictionary parseDictionary)
	{
		IMetaMember callee = ParseUtil.lookupMember(methodID, parseDictionary, model);

		if (callee != null)
		{
			Tag methodTag = parseDictionary.getMethod(methodID);

			String methodBytecodes = methodTag.getAttribute(ATTR_BYTES);
			String invocations = methodTag.getAttribute(ATTR_IICOUNT);

			if (invocations != null)
			{
				int invocationCount = Integer.parseInt(invocations);

				if (invocationCount >= MIN_INLINING_INVOCATIONS)
				{
					String reason = attrs.get(ATTR_REASON);

					double score = 0;

					if (scoreMap.containsKey(reason))
					{
						score = scoreMap.get(reason);
					}
					else
					{
						logger.info("No score is set for reason: {}", reason);
					}

					StringBuilder reasonBuilder = new StringBuilder();

					reasonBuilder.append("The call at bytecode ").append(currentBytecode).append(" to\n");
					reasonBuilder.append("Class: ").append(callee.getMetaClass().getFullyQualifiedName()).append(C_NEWLINE);
					reasonBuilder.append("Member: ").append(callee.toStringUnqualifiedMethodName(false)).append(C_NEWLINE);
					reasonBuilder.append("was not inlined for reason: '").append(reason).append("'\n");

					if (explanationMap.containsKey(reason))
					{
						reasonBuilder.append(explanationMap.get(reason)).append(C_NEWLINE);
					}

					reasonBuilder.append("Invocations: ").append(invocationCount).append(C_NEWLINE);
					reasonBuilder.append("Size of callee bytecode: ").append(methodBytecodes).append(C_NEWLINE);

					score *= invocationCount;

					if (score > 0)
					{
						Suggestion suggestion = new Suggestion(caller, currentBytecode, reasonBuilder.toString(),
								SuggestionType.INLINING, (int) Math.ceil(score));

						if (!suggestionList.contains(suggestion))
						{
							suggestionList.add(suggestion);
						}
					}
				}
			}
			else
			{
				logger.warn("Invocation count missing for methodID: {}", methodID);
			}
		}
	}

	private void handleBranchTag(Map<String, String> attrs, int currentBytecode, IMetaMember caller)
	{
		String countStr = attrs.get(ATTR_BRANCH_COUNT);
		String probStr = attrs.get(ATTR_BRANCH_PROB);

		long count = 0;
		double probability = 0.0;

		if (countStr != null)
		{
			try
			{
				count = Double.valueOf(countStr).longValue();
			}
			catch (NumberFormatException nfe)
			{
				logger.error("Couldn't parse branch tag attribute {}", countStr, nfe);
			}
		}

		if (probStr != null)
		{
			try
			{
				probability = Double.parseDouble(probStr);
			}
			catch (NumberFormatException nfe)
			{
				if (NEVER.equalsIgnoreCase(probStr))
				{
					probability = 0;
				}
				else if (ALWAYS.equalsIgnoreCase(probStr))
				{
					probability = 1;
				}
				else
				{
					logger.error("Unrecognised branch probability: {}", probStr, nfe);
				}
			}
		}

		double score = 0;

		if (probability > 0.45 && probability < 0.55 && count >= MIN_BRANCH_INVOCATIONS)
		{
			score = scoreMap.get(REASON_UNCERTAIN_BRANCH);

			score *= count;
		}

		if (score > 0)
		{
			StringBuilder reasonBuilder = new StringBuilder();

			reasonBuilder.append("Method contains an unpredictable branch at bytecode ");
			reasonBuilder.append(currentBytecode);
			reasonBuilder.append(" that was observed ");
			reasonBuilder.append(count);
			reasonBuilder.append(" times and is taken with probability ");
			reasonBuilder.append(probability);
			reasonBuilder
					.append(". It may be possbile to modify the branch (for example by sorting a Collection before iterating) to make it more predictable.");

			Suggestion suggestion = new Suggestion(caller, currentBytecode, reasonBuilder.toString(), SuggestionType.BRANCH,
					(int) Math.ceil(score));

			if (!suggestionList.contains(suggestion))
			{
				suggestionList.add(suggestion);
			}
		}
	}

	@Override
	public void visitParseTag(Tag parseTag, IParseDictionary parseDictionary) throws LogParseException
	{
		processParseTag(parseTag, metaMember, parseDictionary);
	}
}