/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    // 表示当前task状态
    private volatile int state;
    // 当前任务尚未执行
    private static final int NEW          = 0;
    // 当前任务正在结束，稍微完全结束，一种临界状态
    private static final int COMPLETING   = 1;
    // 当前任务正常结束
    private static final int NORMAL       = 2;
    // 当前任务执行过程中发生了异常，内部封装的callable.run()向上抛出了异常
    private static final int EXCEPTIONAL  = 3;
    // 当前任务被取消
    private static final int CANCELLED    = 4;
    // 当前任务终端中...
    private static final int INTERRUPTING = 5;
    // 当前任务已中断
    private static final int INTERRUPTED  = 6;

    /** The underlying callable; nulled out after running */
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        // 条件成立:  当前任务状态正常结束
        if (s == NORMAL)
        	// 直接返回Callable运算结果
            return (V)x;
        // 条件成立:  当前任务状态
        if (s >= CANCELLED)
            throw new CancellationException();
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
    	// 条件一: state == NEW 成立，表示当前任务处于运行中或者处于线程池任务队列中.
		// 条件二: UNSAFE.compareAndSwapInt(this, stateOffset, NEW, mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
		// 条件成立说明修改状态成功.  false说明cancel失败.
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                	// 执行当前FutureTask的线程，有可能现在是null，是null的情况是: 当前任务在队列中。还没有线程获取到它.
                    Thread t = runner;
                    // 条件成立：说明当前线程runner, 正在执行task
                    if (t != null)
                    	// 给runner线程一个中断信号。如果你的程序是响应终端会走中断逻辑，假设你的程序不是响应终端的，啥也不会发生.
                        t.interrupt();
                } finally { // final state
                	// 设置状态为中断完成
                   UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
        	// 唤醒所有get()线程.
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    // 场景: 多个线程等待当前任务执行完成后的结果 ...
    public V get() throws InterruptedException, ExecutionException {
    	// 获取当前任务状态
        int s = state;
        // 条件成立: 未执行、正在执行、  调用get的外部线程会阻塞在get方法上.
        if (s <= COMPLETING)
            s = awaitDone(false, 0L); // 如果任务还没有完成的话，就等待它完成
        return report(s);// 返回结果或者抛出异常
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;// 设置任务执行得到的结果
			// 设置状态为NORMAL,说明正常结束.
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion(); // 通知所有get的线程
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

 	// 任务执行的入口
    public void run() {

    	// 条件一: state != NEW 条件成立，说明当前task已经被执行过了
		// 或者 被canel了，总之非NEW状态的任务，线程就不处理了
		// 条件二: 通过内存CAS操作将当前线程设置到runnerOffet这个属性
		//    条件成立: cas失败，当前任务被其他线程抢占了
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;

        // 执行到这里，当前task一定是NEW状态，而且当前线程也抢占Task成功
        try {
        	// Callable 就是程序员自己封装逻辑的Callable 或者  装饰的Runnable
            Callable<V> c = callable;
            //
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                	// 调用程序员自己写得Callable  或者 装饰后的Runnable
                    result = c.call();
                    // c.call 未出现任何异常，ran会设置为ture代码执行成功
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    setException(ex);
                }
                if (ran)
                    set(result);
            }
        } finally {
        	// 只有在任务执行期间runner才不会为空.
			// 配合上面的compare and swap操作，给这个任务设置当前执行线程，防止并发多线程执行的情况
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
		// q指向waiters链表的头节点
        for (WaitNode q; (q = waiters) != null;) {
        	// 通过cas的方式将头节点置为空, 因为怕外部线程调用canel()取消当前任务 也会触发finishCompletion方法--- 小概率事件
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
            	// 会通知所有在等待这个结果的线程，并把WaitNode从链表中移除
                for (;;) {
                	// 获取当前node节点封装的thread
                    Thread t = q.thread;
                    // 条件成立: 说明当前线程不为null
                    if (t != null) {
                    	// help GC
                        q.thread = null;
                        // 然后把当前线程唤醒
                        LockSupport.unpark(t);
                    }
                    // next 当前节点的下一个节点
                    WaitNode next = q.next;
                    // 条件成立: 说明是最后一个节点
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        // 一个空方法，可以继承FutureTask来定义不同的实现
        done();

        // 将callable设置为null help GC
        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
    	// 0
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        // 自旋
        for (;;) {
        	// 条件成立: 说明当前线程唤醒，是被其它线程使用中断这种方式唤醒的.
			// interrupted()返回true后会将Thread的中断标记重置到false
            if (Thread.interrupted()) {
            	// 将当前线程出队
                removeWaiter(q);
                // get方法抛出中断异常.
                throw new InterruptedException();
            }

            // 假设当前线程是被其它线程使用unpark(thread)唤醒的话，会正常自旋，走下面逻辑
			// 获取当前任务最新状态
            int s = state;
            // 条件成立: 说明当前任务已经有结果了，可能是好，可能是坏
            if (s > COMPLETING) {
            	// 条件成立: 说明已经为当前线程创建过Node了，此时需要将node.thread = null help GC
                if (q != null)
                    q.thread = null;
                // 直接返回当前状态.
                return s;
            }
            // 条件成立: 说明当前任务接近完成状态。这里让当前线程再释放cpu. 进行下一次抢占cpu.
            else if (s == COMPLETING) // cannot time out yet
            	// 让出cpu的使用权.
                Thread.yield();
            // 条件成立: 第一次自旋, 当前线程还未创建WaitNode对象，此时为当前线程创建WaitNode
            else if (q == null)
                q = new WaitNode();
            // 条件成立: 第二次自旋， 当前线程已经创建WaitNode对象了，但是node对象还未入列.
            else if (!queued)
            	// q.next = waiters; 当前线程node节点next指向  原队列的头节点  waiters一直指向队列的头
            	// cas方式设置waiters应用指向当前线程node, 成功的话 queued == true 否则， 可能其它线程先你一步入列.
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     q.next = waiters, q);
            // 第三次自旋，会到这里.
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            else
            	// 当前get操作线程就会被park了, 线程状态变为WAITING状态，相当于休眠了..
			    // 除非有其它线程把你唤醒  或者 将当前线程终端.
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                	// 当前节点的下一个节点
                    s = q.next;
                    if (q.thread != null)
                    	// 将pred节点直接设置为当前节点，当前节点变为头节点.
                        pred = q;
                    else if (pred != null) {// 当前节点不是头节点,就是在中间部位的
                    	// pred节点直接指向当前节点的下一个节点, 中间的节点就断开了
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
