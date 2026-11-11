/*******************************************************************************
 * Copyright (c) 2026 Vector Informatik GmbH and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Vector Informatik GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.internal.views.markers;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.internal.ide.IDEWorkbenchPlugin;

/**
 * Caches whether a marker has quick fixes. The answer is computed by one
 * background job so that callers on the UI thread never wait for a slow
 * {@link org.eclipse.ui.IMarkerResolutionGenerator2}.
 * <p>
 * Threading contract: {@code IMarkerResolutionGenerator2.hasResolutions} is
 * called from a worker thread of a system job, never from the UI thread, and
 * for one marker at a time. Implementations must be thread safe. Listeners are
 * called from that worker thread.
 * </p>
 * <p>
 * Known issue: the markers are computed one after the other. A marker whose
 * generator is stuck delays the answers for all markers queued behind it.
 * </p>
 */
public final class MarkerFixabilityCache {

	private static final int MAX_ENTRIES = 20000;

	private static final MarkerFixabilityCache INSTANCE = new MarkerFixabilityCache();

	/** Cached answer; {@code stale} answers are served but recomputed. */
	private record Entry(boolean fixable, boolean stale) {
	}

	/** Insertion ordered, guarded by {@link #lock}. */
	private final Map<IMarker, Entry> entries = new LinkedHashMap<>();
	private final Set<IMarker> queue = new LinkedHashSet<>();
	private final Object lock = new Object();
	private final CopyOnWriteArrayList<Consumer<IMarker>> listeners = new CopyOnWriteArrayList<>();
	private final Job job = new Job("Computing marker quick fix availability") { //$NON-NLS-1$
		@Override
		protected IStatus run(IProgressMonitor monitor) {
			return computeQueued(monitor);
		}
	};
	/** Marker being computed right now, guarded by {@link #lock}. */
	private IMarker current;
	/** Generation in which {@link #current} was started, guarded by {@link #lock}. */
	private long currentGeneration;
	/** Bumped by {@link #invalidate()} and {@link #clear()}, guarded by {@link #lock}. */
	private long generation;
	/** Bumped by {@link #clear()}, guarded by {@link #lock}. */
	private long epoch;

	/** Creates a separate cache; production code uses {@link #getInstance()}. */
	public MarkerFixabilityCache() {
		job.setSystem(true);
		job.setPriority(Job.DECORATE);
	}

	/**
	 * @return the shared instance
	 */
	public static MarkerFixabilityCache getInstance() {
		return INSTANCE;
	}

	/**
	 * Returns the last known answer without waiting. Queues a computation when
	 * there is no answer or the answer is stale.
	 *
	 * @param marker the marker to check
	 * @return the last known answer, or {@code null} if none is known yet
	 */
	public Boolean lookup(IMarker marker) {
		Entry entry;
		boolean schedule = false;
		synchronized (lock) {
			entry = entries.get(marker);
			if (entry == null || entry.stale()) {
				schedule = request(marker);
			}
		}
		if (schedule) {
			job.schedule();
		}
		return entry == null ? null : Boolean.valueOf(entry.fixable());
	}

	/** Must hold {@link #lock}. Returns whether the job must be scheduled. */
	private boolean request(IMarker marker) {
		if (marker.equals(current) && currentGeneration == generation) {
			// the running computation already yields an up to date answer
			return false;
		}
		return queue.add(marker);
	}

	private IStatus computeQueued(IProgressMonitor monitor) {
		while (!monitor.isCanceled()) {
			IMarker marker;
			long startGeneration;
			long startEpoch;
			synchronized (lock) {
				Iterator<IMarker> it = queue.iterator();
				if (!it.hasNext()) {
					return Status.OK_STATUS;
				}
				marker = it.next();
				it.remove();
				current = marker;
				currentGeneration = generation;
				startGeneration = generation;
				startEpoch = epoch;
			}
			boolean fixable = false;
			try {
				fixable = IDE.getMarkerHelpRegistry().hasResolutions(marker);
			} catch (RuntimeException e) {
				IDEWorkbenchPlugin.log("Failed to compute quick fix availability", e); //$NON-NLS-1$
			}
			boolean notify;
			synchronized (lock) {
				current = null;
				if (epoch != startEpoch) {
					// cleared while computing: the answer is not wanted any more
					continue;
				}
				boolean outdated = generation != startGeneration;
				Entry previous = entries.get(marker);
				if (previous == null && entries.size() >= MAX_ENTRIES) {
					evict();
				}
				entries.put(marker, new Entry(fixable, outdated));
				if (outdated) {
					queue.add(marker);
				}
				notify = previous == null || previous.fixable() != fixable;
			}
			if (notify) {
				for (Consumer<IMarker> listener : listeners) {
					try {
						listener.accept(marker);
					} catch (RuntimeException e) {
						IDEWorkbenchPlugin.log("Failed to notify about quick fix availability", e); //$NON-NLS-1$
					}
				}
			}
		}
		synchronized (lock) {
			if (!queue.isEmpty()) {
				job.schedule();
			}
		}
		return Status.CANCEL_STATUS;
	}

	/** Must hold {@link #lock}. Drops removed markers first, then the oldest. */
	private void evict() {
		entries.keySet().removeIf(m -> !m.exists());
		int toRemove = entries.size() - (MAX_ENTRIES - MAX_ENTRIES / 10);
		for (Iterator<IMarker> it = entries.keySet().iterator(); toRemove > 0 && it.hasNext(); toRemove--) {
			it.next();
			it.remove();
		}
	}

	/**
	 * Marks all answers as stale. They stay visible until recomputed. Answers of
	 * markers that do not exist any more are dropped.
	 */
	public void invalidate() {
		synchronized (lock) {
			generation++;
			entries.entrySet().removeIf(e -> !e.getKey().exists());
			entries.replaceAll((m, e) -> new Entry(e.fixable(), true));
		}
	}

	/**
	 * Drops all answers and pending computations.
	 */
	public void clear() {
		synchronized (lock) {
			generation++;
			epoch++;
			queue.clear();
			entries.clear();
		}
	}

	/**
	 * Registers a listener called from the background job when the answer for a
	 * marker is new or has changed.
	 *
	 * @param listener the listener
	 */
	public void addListener(Consumer<IMarker> listener) {
		listeners.add(listener);
	}

	/**
	 * Removes the listener. When the last listener is removed, all answers and
	 * pending computations are dropped.
	 *
	 * @param listener the listener to remove
	 */
	public void removeListener(Consumer<IMarker> listener) {
		listeners.remove(listener);
		if (listeners.isEmpty()) {
			clear();
		}
	}
}
