/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.search2.internal.ui.text;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.search.internal.ui.SearchPlugin;
import org.eclipse.search.ui.SearchUI;
import org.eclipse.search.ui.text.ISearchEditorAccess;
import org.eclipse.search.ui.text.Match;
import org.eclipse.search2.internal.ui.InternalSearchUI;
import org.eclipse.search2.internal.ui.SearchMessages;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AnnotationTypeLookup;


public class EditorAccessHighlighter extends Highlighter {
	private ISearchEditorAccess fEditorAcess;
	private Map fMatchesToAnnotations;
	private AnnotationTypeLookup fAnnotationTypeLookup= EditorsUI.getAnnotationTypeLookup();
	
	public EditorAccessHighlighter(ISearchEditorAccess editorAccess) {
		fEditorAcess= editorAccess;
		fMatchesToAnnotations= new HashMap();
	}

	public void addHighlights(Match[] matches) {
		Map mapsByAnnotationModel= new HashMap();
		for (int i= 0; i < matches.length; i++) {
			int offset= matches[i].getOffset();
			int length= matches[i].getLength();
			if (offset >= 0 && length >= 0) {
				try {
					Position position= createPosition(matches[i]);
					if (position != null) {
						Map map= getMap(mapsByAnnotationModel, matches[i]);
						if (map != null) {
							Annotation annotation= new Annotation(fAnnotationTypeLookup.getAnnotationType(SearchUI.SEARCH_MARKER, IMarker.SEVERITY_INFO), true, null);
							fMatchesToAnnotations.put(matches[i], annotation);
							map.put(annotation, position);
						}
					}
				} catch (BadLocationException e) {
					SearchPlugin.log(new Status(IStatus.ERROR, SearchPlugin.getID(), 0, SearchMessages.getString("EditorAccessHighlighter.error.badLocation"), e)); //$NON-NLS-1$
				}
			}
		}
		for (Iterator maps= mapsByAnnotationModel.keySet().iterator(); maps.hasNext();) {
			IAnnotationModel model= (IAnnotationModel) maps.next();
			Map positionMap= (Map) mapsByAnnotationModel.get(model);
			addAnnotations(model, positionMap);
		}
		
	}

	private Position createPosition(Match match) throws BadLocationException {
		Position position= InternalSearchUI.getInstance().getPositionTracker().getCurrentPosition(match);
		if (position == null)
			position= new Position(match.getOffset(), match.getLength());
		if (match.getBaseUnit() == Match.UNIT_LINE) {
			IDocument doc= fEditorAcess.getDocument(match);
			if (doc != null) {
				position= PositionTracker.convertToCharacterPosition(position, doc);
			} else {
				SearchPlugin.log(new Status(IStatus.ERROR, SearchPlugin.getID(), 0, SearchMessages.getString("AnnotationHighlighter.error.noDocument"), null)); //$NON-NLS-1$
				return null;
			}
		}
		return position;
	}

	private Map getMap(Map mapsByAnnotationModel, Match match) {
		IAnnotationModel model= fEditorAcess.getAnnotationModel(match);
		if (model == null)
			return null;
		HashMap map= (HashMap) mapsByAnnotationModel.get(model);
		if (map == null) {
			map= new HashMap();
			mapsByAnnotationModel.put(model, map);
		}
		return map;
	}

	private Set getSet(Map setsByAnnotationModel, Match match) {
		IAnnotationModel model= fEditorAcess.getAnnotationModel(match);
		if (model == null)
			return null;
		HashSet set= (HashSet) setsByAnnotationModel.get(model);
		if (set == null) {
			set= new HashSet();
			setsByAnnotationModel.put(model, set);
		}
		return set;
	}

	public void removeHighlights(Match[] matches) {
		Map setsByAnnotationModel= new HashMap();
		for (int i= 0; i < matches.length; i++) {
			Annotation annotation= (Annotation) fMatchesToAnnotations.remove(matches[i]);
			if (annotation != null) {
				Set annotations= getSet(setsByAnnotationModel, matches[i]);
				if (annotations != null)
					annotations.add(annotation);
			}
		}

		for (Iterator maps= setsByAnnotationModel.keySet().iterator(); maps.hasNext();) {
			IAnnotationModel model= (IAnnotationModel) maps.next();
			Set set= (Set) setsByAnnotationModel.get(model);
			removeAnnotations(model, set);
		}

	}
	
	private void addAnnotations(IAnnotationModel model, Map annotationToPositionMap) {
		if (model instanceof IAnnotationModelExtension) {
			IAnnotationModelExtension ame= (IAnnotationModelExtension) model;
			ame.replaceAnnotations(new Annotation[0], annotationToPositionMap);
		} else {
			for (Iterator elements= annotationToPositionMap.keySet().iterator(); elements.hasNext();) {
				Annotation element= (Annotation) elements.next();
				Position p= (Position) annotationToPositionMap.get(element);
				model.addAnnotation(element, p);
			}
		}
	}
	/**
	 * Removes annotations from the given annotation model. The default implementation works for editors that
	 * implement <code>ITextEditor</code>.
	 * Subclasses may override this method. 
	 * @param annotations A set containing the annotations to be removed.
	 * 			 @see Annotation
	 */
	private void removeAnnotations(IAnnotationModel model, Set annotations) {
		if (model instanceof IAnnotationModelExtension) {
			IAnnotationModelExtension ame= (IAnnotationModelExtension) model;
			Annotation[] annotationArray= new Annotation[annotations.size()];
			ame.replaceAnnotations((Annotation[]) annotations.toArray(annotationArray), Collections.EMPTY_MAP);
		} else {
			for (Iterator iter= annotations.iterator(); iter.hasNext();) {
				Annotation element= (Annotation) iter.next();
				model.removeAnnotation(element);
			}
		}
	}

	public  void removeAll() {
		Set matchSet= fMatchesToAnnotations.keySet();
		Match[] matches= new Match[matchSet.size()];
		removeHighlights((Match[]) matchSet.toArray(matches));
	}

}
