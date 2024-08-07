/*******************************************************************************
 * Copyright (c) 2023 Vector Informatik GmbH and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Vector Informatik GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.internal.findandreplace;

import org.eclipse.jface.text.IFindReplaceTarget;

import org.eclipse.ui.internal.findandreplace.status.IFindReplaceStatus;

/**
 * Implements a generalized logic operator for in-file Find/Replace-Operations.
 * Requires a target that inherits from {@link IFindReplaceTarget} to operate.
 * Allows enabling or disabling different {@link SearchOptions} which will be
 * applied to subsequent operations.
 */
public interface IFindReplaceLogic {

	/**
	 * Activate a search option
	 *
	 * @param searchOption option
	 */
	public void activate(SearchOptions searchOption);

	/**
	 * Deactivate a search option
	 *
	 * @param searchOption option
	 */
	public void deactivate(SearchOptions searchOption);

	/**
	 * @param searchOption option
	 * @return whether the option is active
	 */
	public boolean isActive(SearchOptions searchOption);

	/**
	 * Returns the current status of FindReplaceLogic. The Status can inform about
	 * events such as an error happening, a warning happening (e.g.: the
	 * search-string wasn't found) and brings a method to retrieve a message that
	 * can directly be displayed to the user.
	 *
	 * @return FindAndReplaceMessageStatus
	 */
	public IFindReplaceStatus getStatus();

	/**
	 * RegEx-Search is not possible on every target. Hence, even after {code
	 * activate(SearchOptions.REGEX)}, we need to check, whether we may use
	 * RegEx-Search.
	 *
	 * @return whether RegEx search is currently used
	 */
	public boolean isRegExSearchAvailableAndActive();

	/**
	 * {@return whether incremental search may be performed by the
	 * find/replace-logic based on the currently active options}
	 */
	public boolean isIncrementalSearchAvailable();

	/**
	 * Replaces all occurrences of the user's findString with the replace string.
	 * Indicate to the user the number of replacements that occur.
	 *
	 * @param findString    The string that will be replaced
	 * @param replaceString The string that will replace the findString
	 */
	public void performReplaceAll(String findString, String replaceString);

	/**
	 * Selects all occurrences of findString.
	 *
	 * @param findString The String to find and select
	 */
	public void performSelectAll(String findString);

	/**
	 * Locates the user's findString in the target. If incremental search is
	 * activated, the search will be performed starting from an incremental search
	 * position, which can be reset using {@link #resetIncrementalBaseLocation()}.
	 * If incremental search is activated and RegEx search is activated, nothing
	 * happens.
	 *
	 * @param searchString the String to search for
	 * @return Whether the string was found in the target
	 *
	 */
	public boolean performSearch(String searchString);

	/**
	 * Searches for a string starting at the given offset and using the specified
	 * search directives. If a string has been found it is selected and its start
	 * offset is returned.
	 *
	 * @param offset        the offset at which searching starts
	 * @param findString    the string which should be found
	 * @return the position of the specified string, or -1 if the string has not
	 *         been found
	 */
	public int findAndSelect(int offset, String findString);

	/**
	 * Replaces the selection and jumps to the next occurrence of findString
	 * instantly. Will not fail in case the selection is invalidated, eg. after a
	 * replace operation or after the target was updated
	 *
	 * @param findString    the string to replace
	 * @param replaceString the string to put in place of findString
	 * @return whether a replacement has been performed
	 */
	public boolean performReplaceAndFind(String findString, String replaceString);

	/**
	 * Selects first and then replaces the next occurrence.
	 *
	 * @param findString    the string to replace
	 * @param replaceString the new string that will replace the findString
	 * @return whether a replacement has been performed
	 */
	public boolean performSelectAndReplace(String findString, String replaceString);

	/**
	 * Updates the target on which to perform Find/Replace-operations on.
	 *
	 * @param newTarget     the new target for the FindReplaceLogic
	 * @param canEditTarget whether the target is editable - and thus eligible for
	 *                      replacing. Used, for example, for targets containing
	 *                      read-only-files, where the target is an editable
	 *                      component but the file should not be edited.
	 */
	public void updateTarget(IFindReplaceTarget newTarget, boolean canEditTarget);

	/**
	 * dispose of the FindReplaceLogic, ends the Find/Replace-Session in the
	 * FindReplaceTarget.
	 */
	public void dispose();

	/*
	 * @return the Target that FindReplaceLogic operates on
	 */
	public IFindReplaceTarget getTarget();

	/**
	 * Returns <code>true</code> if searching can be restricted to entire words,
	 * <code>false</code> if not. Searching for whole words requires the given find
	 * string to be an entire word and the regex search option to be disabled.
	 *
	 * @param findString the string that is currently being searched for.
	 * @return <code>true</code> if the search can be restricted to whole words
	 */
	public boolean isWholeWordSearchAvailable(String findString);

	/**
	 * Initializes the anchor used as the starting point for incremental searching.
	 * Subsequent incremental searches will start from the first letter of the
	 * currently selected range in the FindReplaceTarget.
	 *
	 * <p>
	 * The "current selection" refers to the range of text that is currently
	 * highlighted or selected within the FindReplaceTarget. This selection can be
	 * either a single position (if no range is selected) or a range of text.
	 *
	 * <p>
	 * When handling range selections:
	 * <ul>
	 * <li>Forward search operations will use the beginning of the selection as the
	 * starting point.</li>
	 * <li>Backward search operations will use the end of the selection as the
	 * starting point.</li>
	 * </ul>
	 * </p>
	 */
	void resetIncrementalBaseLocation();

}