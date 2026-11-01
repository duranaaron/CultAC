package ac.cult.cultac.utils.latency;

import java.util.Arrays;
import java.util.Comparator;

final class LatencyTaskQueue {
    private static final int MAX_RECYCLED_TASKS = 256;
    private static final int RUNS = 4;
    private static final int OVERFLOW_CAPACITY = 16;
    private static final int RETAINED_OVERFLOW_CAPACITY = 1024;
    private static final Comparator<TaskNode> BY_SEQUENCE = (a, b) -> Long.compare(a.sequence, b.sequence);
    // Each run keeps its tasks in insertion order with non-decreasing transactions, so its ready tasks are a prefix.
    private final TaskNode[] runFirst = new TaskNode[RUNS];
    private final TaskNode[] runLast = new TaskNode[RUNS];
    // Tasks no run could take: a min-heap on (transaction, sequence), so a drain pops only the ready ones.
    private TaskNode[] overflow = new TaskNode[OVERFLOW_CAPACITY];
    private int overflowSize;
    // Ready overflow tasks of one drain, sorted back into insertion order; empty between drains.
    private TaskNode[] overflowReady = new TaskNode[OVERFLOW_CAPACITY];
    // Insertion order across the runs and the overflow; a continuation keeps its task's sequence.
    private long nextSequence;
    private TaskNode recycledTasks;
    private int recycledTaskCount;

    synchronized void add(int transaction, Runnable runnable) {
        add(acquire(transaction, runnable, null));
    }

    synchronized void addWithNextTransaction(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
        add(acquire(transaction, runnable, nextTransactionRunnable));
    }

    private TaskNode acquire(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
        TaskNode task = recycledTasks;
        if (task == null) return new TaskNode(transaction, runnable, nextTransactionRunnable);
        recycledTasks = task.next;
        recycledTaskCount--;
        task.transaction = transaction;
        task.runnable = runnable;
        task.nextTransactionRunnable = nextTransactionRunnable;
        task.next = null;
        return task;
    }

    // The caller must finish walking the drained batch before returning any of its nodes.
    synchronized void recycle(TaskNode task) {
        if (task == null || recycledTaskCount == MAX_RECYCLED_TASKS) return;
        TaskNode tail = task;
        int count = 1;
        while (recycledTaskCount + count < MAX_RECYCLED_TASKS && tail.next != null) {
            tail = tail.next;
            count++;
        }
        tail.next = recycledTasks;
        recycledTasks = task;
        recycledTaskCount += count;
    }

    private TaskNode nextTransactionTask(TaskNode task) {
        if (task.nextTransactionRunnable == null) return null;
        TaskNode continuation = acquire(task.transaction + 1, task.nextTransactionRunnable, null);
        continuation.sequence = task.sequence;
        task.nextTransactionRunnable = null;
        return continuation;
    }

    private void add(TaskNode task) {
        task.sequence = nextSequence++;
        for (int run = 0; run < RUNS; run++) {
            TaskNode last = runLast[run];
            if (last == null) {
                runFirst[run] = task;
                runLast[run] = task;
                return;
            }
            if (last.transaction <= task.transaction) {
                last.next = task;
                runLast[run] = task;
                return;
            }
        }
        pushOverflow(task);
    }

    synchronized TaskNode drainReady(int transaction) {
        if (runFirst[1] == null && runFirst[2] == null && runFirst[3] == null && overflowSize == 0) {
            return drainRun(0, transaction);
        }
        TaskNode ready = null;
        for (int run = 0; run < RUNS; run++) ready = merge(ready, drainRun(run, transaction));
        return merge(ready, drainOverflow(transaction));
    }

    // Chains never share a sequence: a continuation stays in its task's chain, so merging pairwise keeps the order.
    private static TaskNode merge(TaskNode a, TaskNode b) {
        if (a == null) return b;
        if (b == null) return a;
        TaskNode first;
        if (a.sequence < b.sequence) {
            first = a;
            a = a.next;
        } else {
            first = b;
            b = b.next;
        }
        TaskNode last = first;
        while (a != null && b != null) {
            if (a.sequence < b.sequence) {
                last.next = a;
                last = a;
                a = a.next;
            } else {
                last.next = b;
                last = b;
                b = b.next;
            }
        }
        last.next = a != null ? a : b;
        return first;
    }

    /**
     * The run's ready prefix in order, a continuation that is also ready right after its task. Continuations that are
     * not ready take their task's place; they are due at transaction + 1, no later than any task left in the run.
     */
    private TaskNode drainRun(int run, int transaction) {
        TaskNode current = runFirst[run];
        if (current == null || transaction < current.transaction) return null;
        TaskNode readyFirst = null;
        TaskNode readyLast = null;
        TaskNode keptFirst = null;
        TaskNode keptLast = null;
        while (current != null && transaction >= current.transaction) {
            TaskNode next = current.next;
            current.next = null;
            if (readyFirst == null) {
                readyFirst = current;
            } else {
                readyLast.next = current;
            }
            readyLast = current;
            TaskNode continuation = nextTransactionTask(current);
            if (continuation != null) {
                if (continuation.transaction <= transaction) {
                    current.next = continuation;
                    readyLast = continuation;
                } else if (keptFirst == null) {
                    keptFirst = continuation;
                    keptLast = continuation;
                } else {
                    keptLast.next = continuation;
                    keptLast = continuation;
                }
            }
            current = next;
        }
        if (keptFirst == null) {
            runFirst[run] = current;
            if (current == null) runLast[run] = null;
        } else {
            keptLast.next = current;
            runFirst[run] = keptFirst;
            if (current == null) runLast[run] = keptLast;
        }
        return readyFirst;
    }

    /**
     * The overflow's ready tasks in insertion order, a continuation that is also ready right after its task. Pops only
     * ready tasks, so a pong costs the ready work, not the tasks still waiting. A continuation that is not ready goes
     * back in with its task's sequence, which keeps its place in the order.
     */
    private TaskNode drainOverflow(int transaction) {
        int count = 0;
        while (overflowSize > 0 && overflow[0].transaction <= transaction) {
            TaskNode current = popOverflow();
            TaskNode continuation = nextTransactionTask(current);
            if (continuation != null) {
                if (continuation.transaction <= transaction) {
                    current.next = continuation;
                } else {
                    pushOverflow(continuation);
                }
            }
            if (count == overflowReady.length) overflowReady = Arrays.copyOf(overflowReady, count * 2);
            overflowReady[count++] = current;
        }
        if (count == 0) return null;
        Arrays.sort(overflowReady, 0, count, BY_SEQUENCE);
        TaskNode readyFirst = overflowReady[0];
        TaskNode readyLast = null;
        for (int i = 0; i < count; i++) {
            TaskNode task = overflowReady[i];
            overflowReady[i] = null;
            if (readyLast != null) readyLast.next = task;
            readyLast = task.next != null ? task.next : task;
        }
        if (overflowReady.length > RETAINED_OVERFLOW_CAPACITY) overflowReady = new TaskNode[OVERFLOW_CAPACITY];
        return readyFirst;
    }

    private void pushOverflow(TaskNode task) {
        if (overflowSize == overflow.length) overflow = Arrays.copyOf(overflow, overflowSize * 2);
        int index = overflowSize++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (!before(task, overflow[parent])) break;
            overflow[index] = overflow[parent];
            index = parent;
        }
        overflow[index] = task;
    }

    private TaskNode popOverflow() {
        TaskNode top = overflow[0];
        TaskNode last = overflow[--overflowSize];
        overflow[overflowSize] = null;
        if (overflowSize == 0) {
            // A lagging client can grow the heap to thousands of slots; do not keep that for the whole session.
            if (overflow.length > RETAINED_OVERFLOW_CAPACITY) overflow = new TaskNode[OVERFLOW_CAPACITY];
            return top;
        }
        int index = 0;
        int half = overflowSize >>> 1;
        while (index < half) {
            int child = 2 * index + 1;
            int right = child + 1;
            if (right < overflowSize && before(overflow[right], overflow[child])) child = right;
            if (!before(overflow[child], last)) break;
            overflow[index] = overflow[child];
            index = child;
        }
        overflow[index] = last;
        return top;
    }

    private static boolean before(TaskNode a, TaskNode b) {
        return a.transaction < b.transaction || (a.transaction == b.transaction && a.sequence < b.sequence);
    }

    static final class TaskNode {
        private int transaction;
        private long sequence;
        private Runnable runnable;
        private Runnable nextTransactionRunnable;
        private TaskNode next;

        private TaskNode(int transaction, Runnable runnable, Runnable nextTransactionRunnable) {
            this.transaction = transaction;
            this.runnable = runnable;
            this.nextTransactionRunnable = nextTransactionRunnable;
        }

        Runnable runnable() {
            return runnable;
        }

        TaskNode next() {
            return next;
        }

        void clear() {
            runnable = null;
            nextTransactionRunnable = null;
            next = null;
        }

        TaskNode clearAndLink(TaskNode released) {
            clear();
            next = released;
            return this;
        }
    }
}
