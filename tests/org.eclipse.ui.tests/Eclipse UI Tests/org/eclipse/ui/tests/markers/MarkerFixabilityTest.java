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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.core.internal.resources.Workspace;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.WorkbenchPlugin;
import org.eclipse.ui.internal.ide.IDEInternalWorkbenchImages;
import org.eclipse.ui.internal.views.markers.ExtendedMarkersView;
import org.eclipse.ui.internal.views.markers.MarkerFixabilityCache;
import org.eclipse.ui.internal.views.markers.MarkerSupportInternalUtilities;
import org.eclipse.ui.internal.views.markers.MarkersTreeViewer;
import org.eclipse.ui.internal.views.markers.QuickFixPropertyTester;
import org.eclipse.ui.views.markers.MarkerField;
import org.eclipse.ui.views.markers.MarkerItem;
import org.eclipse.ui.views.markers.MarkerSupportView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that the Markers view never waits for slow
 * {@code IMarkerResolutionGenerator2.hasResolutions} implementations on the UI
 * thread.
 */
public class MarkerFixabilityTest {

	private static final long MAX_UI_MILLIS = 1000;

	private static final String PROBLEM_VIEW_ID = "org.eclipse.ui.views.ProblemView";

	private IProject project;
	private IMarker marker;
	private final MarkerField field = new MarkerField() {
		@Override
		public String getValue(MarkerItem item) {
			return "";
		}
	};

	@Before
	public void setUp() throws CoreException {
		project = ResourcesPlugin.getWorkspace().getRoot().getProject("MarkerFixabilityTest");
		if (!project.exists()) {
			project.create(null);
		}
		project.open(null);
		marker = project.createMarker(BlockingResolutionGenerator.MARKER_TYPE);
		marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
		BlockingResolutionGenerator.latch = new CountDownLatch(1);
		BlockingResolutionGenerator.entered = new CountDownLatch(1);
		BlockingResolutionGenerator.written = new CountDownLatch(1);
		BlockingResolutionGenerator.writeMarker = false;
		BlockingResolutionGenerator.answer = true;
		BlockingResolutionGenerator.calls.set(0);
		MarkerFixabilityCache.getInstance().clear();
	}

	@After
	public void tearDown() throws CoreException {
		BlockingResolutionGenerator.latch.countDown();
		BlockingResolutionGenerator.writeMarker = false;
		BlockingResolutionGenerator.answer = true;
		closeProblemsView();
		project.delete(true, null);
		MarkerFixabilityCache.getInstance().clear();
	}

	/** Disposes all open Markers views, which are listeners of the cache. */
	private static void closeProblemsView() {
		for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (var page : window.getPages()) {
				for (var ref : page.getViewReferences()) {
					if (ref.getView(false) instanceof ExtendedMarkersView) {
						page.hideView(ref);
					}
				}
			}
		}
	}

	private MarkerItem item() {
		return new MarkerItem() {
			@Override
			public IMarker getMarker() {
				return marker;
			}
		};
	}

	private static Image errorImage() {
		return MarkerSupportInternalUtilities.getSeverityImage(IMarker.SEVERITY_ERROR);
	}

	private static Image fixableErrorImage() {
		return WorkbenchPlugin.getDefault().getSharedImages()
				.getImage(IDEInternalWorkbenchImages.IMG_OBJS_FIXABLE_ERROR);
	}

	private static void assertReturnsFast(Runnable r) {
		long start = System.currentTimeMillis();
		r.run();
		long elapsed = System.currentTimeMillis() - start;
		assertTrue("UI thread blocked for " + elapsed + " ms", elapsed < MAX_UI_MILLIS);
	}

	private void waitForFixableImage() throws InterruptedException {
		long end = System.currentTimeMillis() + 20000;
		Display display = Display.getCurrent();
		while (System.currentTimeMillis() < end) {
			if (field.annotateImage(item(), errorImage()) == fixableErrorImage()) {
				return;
			}
			while (display != null && display.readAndDispatch()) {
				// process queued UI work
			}
			Thread.sleep(20);
		}
		assertEquals(fixableErrorImage(), field.annotateImage(item(), errorImage()));
	}

	@Test
	public void annotateImageDoesNotBlockOnSlowGenerator() throws Exception {
		Image[] result = new Image[1];
		assertReturnsFast(() -> result[0] = field.annotateImage(item(), errorImage()));
		assertNotEquals("not fixable until computed", fixableErrorImage(), result[0]);
		assertTrue("generator must be asked in background",
				BlockingResolutionGenerator.entered.await(10, TimeUnit.SECONDS));

		assertReturnsFast(() -> field.annotateImage(item(), errorImage()));

		BlockingResolutionGenerator.latch.countDown();
		waitForFixableImage();
	}

	@Test
	public void annotateImageDoesNotBlockWhenGeneratorWritesUnderWorkspaceLock() throws Exception {
		BlockingResolutionGenerator.writeMarker = true;
		Workspace workspace = (Workspace) ResourcesPlugin.getWorkspace();
		IWorkspaceRoot root = workspace.getRoot();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// Hold the workspace lock in protected code, like a refresh does
		Thread holder = new Thread(() -> {
			try {
				workspace.prepareOperation(root, null);
				try {
					workspace.beginOperation(true);
					locked.countDown();
					release.await(60, TimeUnit.SECONDS);
				} finally {
					workspace.endOperation(root, false);
				}
			} catch (CoreException | InterruptedException e) {
				// reported by the assertions below
			}
		});
		holder.start();
		try {
			assertTrue(locked.await(10, TimeUnit.SECONDS));
			assertReturnsFast(() -> field.annotateImage(item(), errorImage()));
			assertTrue("generator must be asked in background",
					BlockingResolutionGenerator.entered.await(10, TimeUnit.SECONDS));
			assertFalse("generator must wait for the workspace lock in setAttribute",
					BlockingResolutionGenerator.written.await(500, TimeUnit.MILLISECONDS));
			assertReturnsFast(() -> field.annotateImage(item(), errorImage()));
		} finally {
			release.countDown();
			holder.join(10000);
		}
		assertTrue("generator writes once the lock is free",
				BlockingResolutionGenerator.written.await(10, TimeUnit.SECONDS));
		BlockingResolutionGenerator.latch.countDown();
		waitForFixableImage();
	}

	@Test
	public void staleAnswerIsRecomputedWhenInvalidatedWhileComputing() throws Exception {
		MarkerFixabilityCache cache = new MarkerFixabilityCache();
		assertNull(cache.lookup(marker));
		assertTrue(BlockingResolutionGenerator.entered.await(10, TimeUnit.SECONDS));
		// the running computation started before this change
		BlockingResolutionGenerator.answer = false;
		cache.invalidate();
		cache.lookup(marker);
		BlockingResolutionGenerator.latch.countDown();
		waitFor("answer is recomputed after the invalidation", () -> Boolean.FALSE.equals(cache.lookup(marker)),
				20000);
	}

	@Test
	public void removingOneListenerKeepsAnswersForTheOther() throws Exception {
		MarkerFixabilityCache cache = new MarkerFixabilityCache();
		BlockingResolutionGenerator.latch.countDown();
		CountDownLatch computed = new CountDownLatch(1);
		Consumer<IMarker> first = m -> {
		};
		Consumer<IMarker> second = m -> {
			if (marker.equals(m)) {
				computed.countDown();
			}
		};
		cache.addListener(first);
		cache.addListener(second);
		cache.lookup(marker);
		assertTrue(computed.await(10, TimeUnit.SECONDS));
		cache.removeListener(first);
		assertEquals(Boolean.TRUE, cache.lookup(marker));
		int calls = BlockingResolutionGenerator.calls.get();
		cache.lookup(marker);
		assertEquals("answer must not be recomputed", calls, BlockingResolutionGenerator.calls.get());
		cache.removeListener(second);
		assertNull("last listener removed drops the answers", cache.lookup(marker));
	}

	@Test
	public void viewItemBecomesFixableWhenAnswerArrives() throws Exception {
		assertItemBecomesFixable(false);
	}

	@Test
	public void viewItemCollapsedWhenAnswerArrivesBecomesFixableOnExpand() throws Exception {
		assertItemBecomesFixable(true);
	}

	private void assertItemBecomesFixable(boolean collapseBeforeAnswer) throws Exception {
		MarkerSupportView view = (MarkerSupportView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
				.getActivePage().showView(PROBLEM_VIEW_ID);
		MarkersTreeViewer viewer = view.getAdapter(MarkersTreeViewer.class);
		assertNotNull(viewer);
		waitFor("marker is shown in the Problems view", () -> {
			viewer.expandAll();
			return findItem(viewer.getTree().getItems()) != null;
		}, 20000);
		TreeItem item = findItem(viewer.getTree().getItems());
		Object elementBefore = item.getData();
		assertTrue("generator must be asked in background",
				BlockingResolutionGenerator.entered.await(10, TimeUnit.SECONDS));
		assertFalse(isFixable(item));
		viewer.setSelection(new StructuredSelection(elementBefore));

		if (collapseBeforeAnswer) {
			viewer.collapseAll();
		}
		BlockingResolutionGenerator.latch.countDown();
		MarkerFixabilityCache cache = MarkerFixabilityCache.getInstance();
		waitFor("answer is computed", () -> Boolean.TRUE.equals(cache.lookup(marker)), 20000);
		if (collapseBeforeAnswer) {
			// collapsed items are created with the answer when they are expanded
			viewer.expandAll();
		}
		waitFor("item shows the quick fix image", () -> isFixable(findItem(viewer.getTree().getItems())), 20000);
		TreeItem after = findItem(viewer.getTree().getItems());
		assertTrue(isFixable(after));
		if (!collapseBeforeAnswer) {
			assertSame("the viewer element must not be replaced", elementBefore, after.getData());
		}
		assertEquals("selection must stay unique", 1, view.getSelectedMarkers().length);
	}

	private TreeItem findItem(TreeItem[] items) {
		for (TreeItem treeItem : items) {
			Object data = treeItem.getData();
			if (data instanceof MarkerItem markerItem && marker.equals(markerItem.getMarker())) {
				return treeItem;
			}
			TreeItem found = findItem(treeItem.getItems());
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static boolean isFixable(TreeItem item) {
		if (item == null) {
			return false;
		}
		int columns = Math.max(1, item.getParent().getColumnCount());
		for (int i = 0; i < columns; i++) {
			if (item.getImage(i) == fixableErrorImage()) {
				return true;
			}
		}
		return false;
	}

	private static void waitFor(String message, BooleanSupplier condition, long millis)
			throws InterruptedException {
		long end = System.currentTimeMillis() + millis;
		Display display = Display.getCurrent();
		while (System.currentTimeMillis() < end) {
			while (display != null && display.readAndDispatch()) {
				// process queued UI work
			}
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
		fail("Timed out waiting for: " + message);
	}

	@Test
	public void quickFixPropertyTesterDoesNotBlockOnSlowGenerator() throws Exception {
		// MarkerEntry is package-private, so the tester is called with it reflectively
		Class<?> entryClass = Class.forName("org.eclipse.ui.internal.views.markers.MarkerEntry");
		var ctor = entryClass.getDeclaredConstructor(IMarker.class);
		ctor.setAccessible(true);
		Object entry = ctor.newInstance(marker);
		QuickFixPropertyTester tester = new QuickFixPropertyTester();
		boolean[] result = new boolean[1];
		assertReturnsFast(() -> result[0] = tester.test(entry, "quickFix", null, null));
		assertTrue("unknown state is conservatively fixable", result[0]);
	}
}
