
/* 
 * Copyright 2004-2005 OpenSymphony 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not 
 * use this file except in compliance with the License. You may obtain a copy 
 * of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT 
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the 
 * License for the specific language governing permissions and limitations 
 * under the License.
 * 
 */

/*
 * Previously Copyright (c) 2001-2004 James House
 */
package org.quartz.core;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobPersistenceException;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.spi.TriggerFiredBundle;

import java.util.Random;

/**
 * <p>
 * The thread responsible for performing the work of firing <code>{@link Trigger}</code>
 * s that are registered with the <code>{@link QuartzScheduler}</code>.
 * </p>
 * 
 * @see QuartzScheduler
 * @see org.quartz.Job
 * @see Trigger
 * 
 * @author James House
 */
public class QuartzSchedulerThread extends Thread {
    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */
    private QuartzScheduler qs;

    private QuartzSchedulerResources qsRsrcs;

    private Object sigLock = new Object();

    private boolean signaled;
    private long signaledNextFireTime;
    
    private boolean paused;

    private boolean halted;

    private SchedulingContext ctxt = null;

    private Random random = new Random(System.currentTimeMillis());

    // When the scheduler finds there is no current trigger to fire, how long
    // it should wait until checking again...
    private static long DEFAULT_IDLE_WAIT_TIME = 30L * 1000L;

    private long idleWaitTime = DEFAULT_IDLE_WAIT_TIME;

    private int idleWaitVariablness = 7 * 1000;

    private long dbFailureRetryInterval = 15L * 1000L;

    private final Log log = LogFactory.getLog(getClass());

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constructors.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a non-daemon <code>Thread</code>
     * with normal priority.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs,
            SchedulingContext ctxt) {
        this(qs, qsRsrcs, ctxt, qsRsrcs.getMakeSchedulerThreadDaemon(), Thread.NORM_PRIORITY);
    }

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a <code>Thread</code> with the given
     * attributes.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs,
            SchedulingContext ctxt, boolean setDaemon, int threadPrio) {
        super(qs.getSchedulerThreadGroup(), qsRsrcs.getThreadName());
        this.qs = qs;
        this.qsRsrcs = qsRsrcs;
        this.ctxt = ctxt;
        this.setDaemon(setDaemon);
        this.setPriority(threadPrio);

        // start the underlying thread, but put this object into the 'paused'
        // state
        // so processing doesn't start yet...
        paused = true;
        halted = false;
        this.start();
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    void setIdleWaitTime(long waitTime) {
        idleWaitTime = waitTime;
        idleWaitVariablness = (int) (waitTime * 0.2);
    }

    private long getDbFailureRetryInterval() {
        return dbFailureRetryInterval;
    }

    public void setDbFailureRetryInterval(long dbFailureRetryInterval) {
        this.dbFailureRetryInterval = dbFailureRetryInterval;
    }

    private long getRandomizedIdleWaitTime() {
        return idleWaitTime - random.nextInt(idleWaitVariablness);
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * </p>
     */
    void togglePause(boolean pause) {
        synchronized (sigLock) {
            paused = pause;

            if (paused) {
                signalSchedulingChange(0);
            } else {
                sigLock.notify();
            }
        }
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * </p>
     */
    void halt() {
        synchronized (sigLock) {
            halted = true;

            if (paused) {
                sigLock.notify();
            } else {
                signalSchedulingChange(0);
            }
        }
    }

    boolean isPaused() {
        return paused;
    }

    /**
     * <p>
     * Signals the main processing loop that a change in scheduling has been
     * made - in order to interrupt any sleeping that may be occuring while
     * waiting for the fire time to arrive.
     * </p>
     *
     * @param newNextTime the time (in millis) when the newly scheduled trigger
     * will fire.  If this method is being called do to some other even (rather
     * than scheduling a trigger), the caller should pass zero (0).
     */
    public void signalSchedulingChange(long candidateNewNextFireTime) {
        synchronized(sigLock) {
            signaled = true;
            signaledNextFireTime = candidateNewNextFireTime;
        }
    }

    public void clearSignaledSchedulingChange() {
        synchronized(sigLock) {
            signaled = false;
            signaledNextFireTime = 0;
        }
    }

    public boolean isScheduleChanged() {
        synchronized(sigLock) {
            return signaled;
        }
    }

    public long getSignaledNextFireTime() {
        synchronized(sigLock) {
            return signaledNextFireTime;
        }
    }

    /**
     * <p>
     * The main processing loop of the <code>QuartzSchedulerThread</code>.
     * </p>
     */
    public void run() {
        boolean lastAcquireFailed = false;
        
        while (!halted) {
            try {
                // check if we're supposed to pause...
                synchronized (sigLock) {
                    while (paused && !halted) {
                        try {
                            // wait until togglePause(false) is called...
                            sigLock.wait(100L);
                        } catch (InterruptedException ignore) {
                        }
                    }
    
                    if (halted) {
                        break;
                    }
                }

                int availTreadCount = qsRsrcs.getThreadPool().blockForAvailableThreads();
                if(availTreadCount > 0) {

                    Trigger trigger = null;

                    long now = System.currentTimeMillis();

                    clearSignaledSchedulingChange();
                    try {
                        trigger = qsRsrcs.getJobStore().acquireNextTrigger(
                                ctxt, now + idleWaitTime);
                        lastAcquireFailed = false;
                    } catch (JobPersistenceException jpe) {
                        if(!lastAcquireFailed) {
                            qs.notifySchedulerListenersError(
                                "An error occured while scanning for the next trigger to fire.",
                                jpe);
                        }
                        lastAcquireFailed = true;
                    } catch (RuntimeException e) {
                        if(!lastAcquireFailed) {
                            getLog().error("quartzSchedulerThreadLoop: RuntimeException "
                                    +e.getMessage(), e);
                        }
                        lastAcquireFailed = true;
                    }

                    if (trigger != null) {

                        now = System.currentTimeMillis();
                        long triggerTime = trigger.getNextFireTime().getTime();
                        long timeUntilTrigger = triggerTime - now;
                        long spinInterval = 10;

                        // this looping may seem a bit silly, but it's the
                        // current work-around
                        // for a dead-lock that can occur if the Thread.sleep()
                        // is replaced with
                        // a obj.wait() that gets notified when the signal is
                        // set...
                        // so to be able to detect the signal change without
                        // sleeping the entire
                        // timeUntilTrigger, we spin here... don't worry
                        // though, this spinning
                        // doesn't even register 0.2% cpu usage on a pentium 4.
                        long numPauses = (timeUntilTrigger / spinInterval);
                        while (numPauses >= 0) {

                            try {
                                Thread.sleep(spinInterval);
                            } catch (InterruptedException ignore) {
                            }
                            
                            if (isScheduleChanged()) {
                            	if(isCandidateNewTimeEarlierWithinReason(triggerTime)) {
                            		// above call does a clearSignaledSchedulingChange()
                            		try {
    	                                qsRsrcs.getJobStore().releaseAcquiredTrigger(
    	                                        ctxt, trigger);
    	                            } catch (JobPersistenceException jpe) {
    	                                qs.notifySchedulerListenersError(
    	                                        "An error occured while releasing trigger '"
    	                                                + trigger.getFullName() + "'",
    	                                        jpe);
    	                                // db connection must have failed... keep
    	                                // retrying until it's up...
    	                                releaseTriggerRetryLoop(trigger);
    	                            } catch (RuntimeException e) {
    	                                getLog().error(
    	                                    "releaseTriggerRetryLoop: RuntimeException "
    	                                    +e.getMessage(), e);
    	                                // db connection must have failed... keep
    	                                // retrying until it's up...
    	                                releaseTriggerRetryLoop(trigger);
    	                            }
    	                            trigger = null;
    	                            break;
                            	}
                            }

                            now = System.currentTimeMillis();
                            timeUntilTrigger = triggerTime - now;
                            numPauses = (timeUntilTrigger / spinInterval);
                        }

                        if(trigger == null)
                        	continue;
                        
                        // set trigger to 'executing'
                        TriggerFiredBundle bndle = null;

                        synchronized(sigLock) {
                            if(!halted) {
                                try {
                                    bndle = qsRsrcs.getJobStore().triggerFired(ctxt,
                                            trigger);
                                } catch (SchedulerException se) {
                                    qs.notifySchedulerListenersError(
                                            "An error occured while firing trigger '"
                                                    + trigger.getFullName() + "'", se);
                                } catch (RuntimeException e) {
                                    getLog().error(
                                        "RuntimeException while firing trigger " +
                                        trigger.getFullName(), e);
                                    // db connection must have failed... keep
                                    // retrying until it's up...
                                    releaseTriggerRetryLoop(trigger);
                                }
                            }

                            // it's possible to get 'null' if the trigger was paused,
                            // blocked, or other similar occurences that prevent it being
                            // fired at this time...  or if the scheduler was shutdown (halted)
                            if (bndle == null) {
                                try {
                                    qsRsrcs.getJobStore().releaseAcquiredTrigger(ctxt,
                                            trigger);
                                } catch (SchedulerException se) {
                                    qs.notifySchedulerListenersError(
                                            "An error occured while releasing trigger '"
                                                    + trigger.getFullName() + "'", se);
                                    // db connection must have failed... keep retrying
                                    // until it's up...
                                    releaseTriggerRetryLoop(trigger);
                                }
                                continue;
                            }

                            // TODO: improvements:
                            //
                            // 2- make sure we can get a job runshell before firing trigger, or
                            //   don't let that throw an exception (right now it never does,
                            //   but the signature says it can).
                            // 3- acquire more triggers at a time (based on num threads available?)


                            JobRunShell shell = null;
                            try {
                                shell = qsRsrcs.getJobRunShellFactory().borrowJobRunShell();
                                shell.initialize(qs, bndle);
                            } catch (SchedulerException se) {
                                try {
                                    qsRsrcs.getJobStore().triggeredJobComplete(ctxt,
                                            trigger, bndle.getJobDetail(), Trigger.INSTRUCTION_SET_ALL_JOB_TRIGGERS_ERROR);
                                } catch (SchedulerException se2) {
                                    qs.notifySchedulerListenersError(
                                            "An error occured while placing job's triggers in error state '"
                                                    + trigger.getFullName() + "'", se2);
                                    // db connection must have failed... keep retrying
                                    // until it's up...
                                    errorTriggerRetryLoop(bndle);
                                }
                                continue;
                            }

                            if (qsRsrcs.getThreadPool().runInThread(shell) == false) {
                                try {
                                    // this case should never happen, as it is indicative of the
                                    // scheduler being shutdown or a bug in the thread pool or
                                    // a thread pool being used concurrently - which the docs
                                    // say not to do...
                                    getLog().error("ThreadPool.runInThread() return false!");
                                    qsRsrcs.getJobStore().triggeredJobComplete(ctxt,
                                            trigger, bndle.getJobDetail(), Trigger.INSTRUCTION_SET_ALL_JOB_TRIGGERS_ERROR);
                                } catch (SchedulerException se2) {
                                    qs.notifySchedulerListenersError(
                                            "An error occured while placing job's triggers in error state '"
                                                    + trigger.getFullName() + "'", se2);
                                    // db connection must have failed... keep retrying
                                    // until it's up...
                                    releaseTriggerRetryLoop(trigger);
                                }
                            }
                        }

                        continue;
                    }
                } else { // if(availTreadCount > 0)
                    continue; // should never happen, if threadPool.blockForAvailableThreads() follows contract
                }

                // this looping may seem a bit silly, but it's the current
                // work-around
                // for a dead-lock that can occur if the Thread.sleep() is replaced
                // with
                // a obj.wait() that gets notified when the signal is set...
                // so to be able to detect the signal change without sleeping the
                // entier
                // getRandomizedIdleWaitTime(), we spin here... don't worry though,
                // the
                // CPU usage of this spinning can't even be measured on a pentium
                // 4.
                long now = System.currentTimeMillis();
                long waitTime = now + getRandomizedIdleWaitTime();
                long timeUntilContinue = waitTime - now;
                long spinInterval = 10;
                long numPauses = (timeUntilContinue / spinInterval);
    
                while (numPauses > 0) {
                	if(isScheduleChanged()) 
                		break;
    
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ignore) {
                    }
    
                    now = System.currentTimeMillis();
                    timeUntilContinue = waitTime - now;
                    numPauses = (timeUntilContinue / spinInterval);
                }
            } catch(RuntimeException re) {
                getLog().error("Runtime error occured in main trigger firing loop.", re);
            }
        } // loop...

        // drop references to scheduler stuff to aid garbage collection...
        qs = null;
        qsRsrcs = null;
    }

    private boolean isCandidateNewTimeEarlierWithinReason(long oldTime) {
    	
		// So here's the deal: We know due to being signaled that 'the schedule'
		// has changed.  We may know (if getSignaledNextFireTime() != 0) the
		// new earliest fire time.  We may not (in which case we will assume
		// that the new time is earlier than the trigger we have acquired).
		// In either case, we only want to abandon our acquired trigger and
		// go looking for a new one if "it's worth it".  It's only worth it if
		// the time cost incurred to abandon the trigger and acquire a new one 
		// is less than the time until the currently acquired trigger will fire,
		// otherwise we're just "thrashing" the job store (e.g. database).
		//
		// So the question becomes when is it "worth it"?  This will depend on
		// the job store implementation (and of course the particular database
		// or whatever behind it).  Ideally we would depend on the job store 
		// implementation to tell us the amount of time in which it "thinks"
		// it can abandon the acquired trigger and acquire a new one.  However
		// we have no current facility for having it tell us that, so we make
		// and somewhat educated but arbitrary guess ;-).

    	synchronized(sigLock) {
			
			boolean earlier = false;
			
			if(getSignaledNextFireTime() == 0)
				earlier = true;
			else if(getSignaledNextFireTime() < oldTime )
				earlier = true;
			
			if(earlier) {
				// so the new time is considered earlier, but is it enough earlier?
				long diff = System.currentTimeMillis() - oldTime;
				if(diff < (qsRsrcs.getJobStore().supportsPersistence() ? 120L : 10L))
					earlier = false;
			}
			
			clearSignaledSchedulingChange();
			
			return earlier;
        }
	}

	public void errorTriggerRetryLoop(TriggerFiredBundle bndle) {
        int retryCount = 0;
        try {
            while (!halted) {
                try {
                    Thread.sleep(getDbFailureRetryInterval()); // retry every N
                    // seconds (the db
                    // connection must
                    // be failed)
                    retryCount++;
                    qsRsrcs.getJobStore().triggeredJobComplete(ctxt,
                            bndle.getTrigger(), bndle.getJobDetail(), Trigger.INSTRUCTION_SET_ALL_JOB_TRIGGERS_ERROR);
                    retryCount = 0;
                    break;
                } catch (JobPersistenceException jpe) {
                    if(retryCount % 4 == 0) {
                        qs.notifySchedulerListenersError(
                            "An error occured while releasing trigger '"
                                    + bndle.getTrigger().getFullName() + "'", jpe);
                    }
                } catch (RuntimeException e) {
                    getLog().error("releaseTriggerRetryLoop: RuntimeException "+e.getMessage(), e);
                } catch (InterruptedException e) {
                    getLog().error("releaseTriggerRetryLoop: InterruptedException "+e.getMessage(), e);
                }
            }
        } finally {
            if(retryCount == 0) {
                getLog().info("releaseTriggerRetryLoop: connection restored.");
            }
        }
    }
    
    public void releaseTriggerRetryLoop(Trigger trigger) {
        int retryCount = 0;
        try {
            while (!halted) {
                try {
                    Thread.sleep(getDbFailureRetryInterval()); // retry every N
                    // seconds (the db
                    // connection must
                    // be failed)
                    retryCount++;
                    qsRsrcs.getJobStore().releaseAcquiredTrigger(ctxt, trigger);
                    retryCount = 0;
                    break;
                } catch (JobPersistenceException jpe) {
                    if(retryCount % 4 == 0) {
                        qs.notifySchedulerListenersError(
                            "An error occured while releasing trigger '"
                                    + trigger.getFullName() + "'", jpe);
                    }
                } catch (RuntimeException e) {
                    getLog().error("releaseTriggerRetryLoop: RuntimeException "+e.getMessage(), e);
                } catch (InterruptedException e) {
                    getLog().error("releaseTriggerRetryLoop: InterruptedException "+e.getMessage(), e);
                }
            }
        } finally {
            if(retryCount == 0) {
                getLog().info("releaseTriggerRetryLoop: connection restored.");
            }
        }
    }
    
    public Log getLog() {
        return log;
    }

} // end of QuartzSchedulerThread
