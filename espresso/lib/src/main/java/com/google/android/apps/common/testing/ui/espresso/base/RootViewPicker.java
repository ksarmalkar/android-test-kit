package com.google.android.apps.common.testing.ui.espresso.base;

import android.app.Activity;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.google.android.apps.common.testing.testrunner.ActivityLifecycleMonitor;
import com.google.android.apps.common.testing.testrunner.Stage;
import com.google.android.apps.common.testing.ui.espresso.NoActivityResumedException;
import com.google.android.apps.common.testing.ui.espresso.NoMatchingRootException;
import com.google.android.apps.common.testing.ui.espresso.Root;
import com.google.android.apps.common.testing.ui.espresso.UiController;
import com.google.android.apps.common.testing.ui.espresso.matcher.RootMatchers;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import org.hamcrest.Matcher;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import static com.google.android.apps.common.testing.ui.espresso.matcher.RootMatchers.isDialog;
import static com.google.android.apps.common.testing.ui.espresso.matcher.RootMatchers.isFocusable;
import static com.google.common.base.Preconditions.checkState;

/**
 * Provides the root View of the top-most Window, with which the user can interact. View is
 * guaranteed to be in a stable state - i.e. not pending any updates from the application.
 *
 * This provider can only be accessed from the main thread.
 */
@Singleton
public final class RootViewPicker implements Provider<List<View>> {

    private static final String TAG = RootViewPicker.class.getSimpleName();

    private final Provider<List<Root>> rootsOracle;
    private final UiController uiController;
    private final ActivityLifecycleMonitor activityLifecycleMonitor;
    private final AtomicReference<Matcher<Root>> rootMatcherRef;

    // this is really just used for composing error messages accurately since returning
    // both the selected roots, and all roots from findRoots() would be hideous
    private transient List<Root> allRoots;

    @Inject
    RootViewPicker(Provider<List<Root>> rootsOracle, UiController uiController,
                   ActivityLifecycleMonitor activityLifecycleMonitor,
                   AtomicReference<Matcher<Root>> rootMatcherRef) {
        this.rootsOracle = rootsOracle;
        this.uiController = uiController;
        this.activityLifecycleMonitor = activityLifecycleMonitor;
        this.rootMatcherRef = rootMatcherRef;
    }

    @Override
    public List<View> get() {
        checkState(Looper.getMainLooper().equals(Looper.myLooper()), "must be called on main thread.");
        Matcher<Root> rootMatcher = rootMatcherRef.get();

        List<Root> roots = findRoots(rootMatcher);

        // we only want to propagate a root view that the user can interact with and is not
        // about to relay itself out. An app should be in this state the majority of the time,
        // if we happen not to be in this state at the moment, process the queue some more
        // we should come to it quickly enough.
        int loops = 0;

        while (!isReady(roots, (rootMatcher instanceof RootMatchers.MultiRootMatcher))) {
            if (loops < 3) {
                uiController.loopMainThreadUntilIdle();
            } else if (loops < 1001) {

                // loopUntil idle effectively is polling and pegs the CPU... if we don't have an update to
                // process immediately, we might have something coming very very soon.
                uiController.loopMainThreadForAtLeast(10);
            } else {
                // we've waited for the root view to be fully laid out and have window focus
                // for over 10 seconds. something is wrong.
                throw new RuntimeException(String.format("Waited for the selected roots of the view hierarchy to have"
                        + " window focus and not be requesting layout for over 10 seconds. If you specified a"
                        + " non default root matcher, it may be picking roots that never take focus."
                        + " Otherwise, something is seriously wrong. Selected Roots:\n%s\n. All Roots:\n%s"
                        , Joiner.on("\n").join(roots), Joiner.on("\n").join(allRoots)));
            }

            roots = findRoots(rootMatcher);
            loops++;
        }

        return Lists.transform(roots, new Function<Root, View>() {
            @Nullable @Override
            public View apply(@Nullable Root root) {
                return root.getDecorView();
            }
        });
    }

    private boolean isReady(List<Root> roots, boolean ignoreFocus) {
        boolean ready = true;
        for (Root root : roots) {
            // Root is ready (i.e. UI is no longer in flux) if layout of the root view is not being
            // requested and the root view has window focus (if it is focusable).
            View rootView = root.getDecorView();
            if (!rootView.isLayoutRequested()) {
                ready &= ignoreFocus || rootView.hasWindowFocus() || !isFocusable().matches(root);
            } else {
                return false;
            }
        }
        return ready;
    }

    private List<Root> findRoots(Matcher<Root> rootMatcher) {
        waitForAtLeastOneActivityToBeResumed();

        allRoots = rootsOracle.get();

        // TODO(user): move these checks into the RootsOracle.
        if (allRoots.isEmpty()) {
            // Reflection broke
            throw new RuntimeException("No root window were discovered.");
        }

        if (allRoots.size() > 1) {
            // Multiple roots only occur:
            // when multiple activities are in some state of their lifecycle in the application
            // - we don't care about this, since we only want to interact with the RESUMED
            // activity, all other activities windows are not visible to the user so, out of
            // scope.
            // when a PopupWindow or PopupMenu is used
            // - this is a case where we definitely want to consider the top most window, since
            // it probably has the most useful info in it.
            // when an android.app.dialog is shown
            // - again, this is getting all the users attention, so it gets the test attention
            // too.

            // If we're actually looking for multiple roots, obviously don't warn about it
            if (Log.isLoggable(TAG, Log.VERBOSE) && !(rootMatcher instanceof RootMatchers.MultiRootMatcher)) {
                Log.v(TAG, String.format("Multiple windows detected: %s", allRoots));
            }
        }

        List<Root> selectedRoots = Lists.newArrayList();
        for (Root root : allRoots) {
            if (rootMatcher.matches(root)) {
                selectedRoots.add(root);
            }
        }

        if (selectedRoots.isEmpty()) {
            throw NoMatchingRootException.create(rootMatcher, allRoots);
        }

        if (rootMatcher instanceof RootMatchers.MultiRootMatcher)
            return selectedRoots;
        else
            return Collections.singletonList(pickBestRoot(selectedRoots));
    }

    private void waitForAtLeastOneActivityToBeResumed() {
        Collection<Activity> resumedActivities =
                activityLifecycleMonitor.getActivitiesInStage(Stage.RESUMED);
        if (resumedActivities.isEmpty()) {
            uiController.loopMainThreadUntilIdle();
            resumedActivities = activityLifecycleMonitor.getActivitiesInStage(Stage.RESUMED);
        }
        if (resumedActivities.isEmpty()) {
            List<Activity> activities = Lists.newArrayList();
            for (Stage s : EnumSet.range(Stage.PRE_ON_CREATE, Stage.RESTARTED)) {
                activities.addAll(activityLifecycleMonitor.getActivitiesInStage(s));
            }
            if (activities.isEmpty()) {
                throw new RuntimeException("No activities found. Did you forget to launch the activity "
                        + "by calling getActivity() or startActivitySync or similar?");
            }
            // well at least there are some activities in the pipeline - lets see if they resume.

            long[] waitTimes =
                    {10, 50, 100, 500, TimeUnit.SECONDS.toMillis(2), TimeUnit.SECONDS.toMillis(30)};

            for (int waitIdx = 0; waitIdx < waitTimes.length; waitIdx++) {
                Log.w(TAG, "No activity currently resumed - waiting: " + waitTimes[waitIdx]
                        + "ms for one to appear.");
                uiController.loopMainThreadForAtLeast(waitTimes[waitIdx]);
                resumedActivities = activityLifecycleMonitor.getActivitiesInStage(Stage.RESUMED);
                if (!resumedActivities.isEmpty()) {
                    return; // one of the pending activities has resumed
                }
            }
            throw new NoActivityResumedException("No activities in stage RESUMED. Did you forget to "
                    + "launch the activity. (test.getActivity() or similar)?");
        }
    }

    private Root pickBestRoot(List<Root> subpanels) {
        Root topSubpanel = subpanels.get(0);
        if (subpanels.size() >= 1) {
            for (Root subpanel : subpanels) {
                if (isDialog().matches(subpanel)) {
                    return subpanel;
                }
                if (subpanel.getWindowLayoutParams().get().type
                        > topSubpanel.getWindowLayoutParams().get().type) {
                    topSubpanel = subpanel;
                }
            }
        }
        return topSubpanel;
    }
}
