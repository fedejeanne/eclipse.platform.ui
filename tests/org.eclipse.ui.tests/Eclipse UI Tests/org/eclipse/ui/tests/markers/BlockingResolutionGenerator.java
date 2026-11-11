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
package org.eclipse.ui.tests.markers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IMarkerResolutionGenerator2;

/**
 * Resolution generator whose {@link #hasResolutions(IMarker)} blocks until
 * {@link #latch} is released and optionally writes to the marker first.
 */
public class BlockingResolutionGenerator implements IMarkerResolutionGenerator2 {

	public static final String MARKER_TYPE = "org.eclipse.ui.tests.blockingResolutionMarker";

	public static volatile CountDownLatch latch = new CountDownLatch(0);
	public static volatile CountDownLatch entered = new CountDownLatch(0);
	/** Counted down once the marker has been written. */
	public static volatile CountDownLatch written = new CountDownLatch(0);
	public static volatile boolean writeMarker;
	/** Answer given by calls that start while it has this value. */
	public static volatile boolean answer = true;
	public static final AtomicInteger calls = new AtomicInteger();

	@Override
	public boolean hasResolutions(IMarker marker) {
		calls.incrementAndGet();
		boolean result = answer;
		entered.countDown();
		try {
			if (writeMarker) {
				marker.setAttribute("probe", System.nanoTime());
				written.countDown();
			}
			latch.await(60, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (CoreException e) {
			return false;
		}
		return result;
	}

	@Override
	public IMarkerResolution[] getResolutions(IMarker marker) {
		return new IMarkerResolution[0];
	}
}
