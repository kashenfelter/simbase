package com.guokr.simbase.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.guokr.simbase.SimCallback;
import com.guokr.simbase.SimContext;
import com.guokr.simbase.SimEngine;
import com.guokr.simbase.errors.SimEngineException;
import com.guokr.simbase.errors.SimErrors;
import com.guokr.simbase.events.BasisListener;
import com.guokr.simbase.events.RecommendationListener;
import com.guokr.simbase.events.SimBasisListener;
import com.guokr.simbase.events.VectorSetListener;
import com.guokr.simbase.store.Basis;

public class SimEngineImpl implements SimEngine, SimBasisListener {

    enum Kind {
        BASIS, VECTORS, RECOMM
    };

    private static final Logger logger = LoggerFactory.getLogger(SimEngineImpl.class);

    public abstract class AsyncSafeRunner implements Runnable {
        String scope;

        public AsyncSafeRunner(String scope) {
            this.scope = scope;
        }

        public abstract void invoke();

        @Override
        public void run() {
            try {
                invoke();
            } catch (Throwable ex) {
                int code = SimErrors.lookup(scope, ex);
                logger.error(SimErrors.info(code), ex);
            }
        }
    }

    public abstract class SafeRunner implements Runnable {
        SimCallback callback;
        String      scope;

        public SafeRunner(String scope, SimCallback callback) {
            this.scope = scope;
            this.callback = callback;
        }

        public abstract void invoke();

        @Override
        public void run() {
            try {
                invoke();
            } catch (Throwable ex) {
                String errMsg = ex.getMessage();
                logger.error(errMsg, ex);
                callback.error(errMsg);
            } finally {
                callback.response();
            }
        }
    }

    public class ServerThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r);
        }

    }

    public class RejectedHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            logger.error("server reject request");
        }

    }

    private SimContext                         context;

    private final ExecutorService              mngmExec    = Executors.newSingleThreadExecutor();
    private final Map<String, Kind>            kindOf      = new HashMap<String, Kind>();
    private final Map<String, String>          basisOf     = new HashMap<String, String>();
    private final Map<String, List<String>>    vectorsOf   = new HashMap<String, List<String>>();
    private final Map<String, Set<String>>     rtargetsOf  = new HashMap<String, Set<String>>();
    private final Map<String, Set<String>>     rsourcesOf  = new HashMap<String, Set<String>>();
    private final Map<String, SimBasis>        bases       = new HashMap<String, SimBasis>();

    private final Map<String, ExecutorService> writerExecs = new HashMap<String, ExecutorService>();
    private final ThreadPoolExecutor           readerPool  = new ThreadPoolExecutor(53, 83, 37, TimeUnit.SECONDS,
                                                                   new ArrayBlockingQueue<Runnable>(100),
                                                                   new ServerThreadFactory(), new RejectedHandler());

    private final Map<String, Integer>         counters    = new HashMap<String, Integer>();
    private final int                          bycount;
    private final String                       savePath;

    public SimEngineImpl(SimContext simContext) {
        String separator = System.getProperty("file.separator");
        this.context = simContext;
        this.bycount = simContext.getInt("bycount");
        this.savePath = new StringBuilder(System.getProperty("user.dir")).append(separator)
                .append(context.getString("savepath")).append(separator).toString();
        this.load(null);
        this.startCron();
    }

    private void validatePath(String filePath) throws SimEngineException {
        if (!new File(filePath).exists()) {
            throw new SimEngineException(String.format("Dmp file '%s' not exists", filePath));
        }
    }

    private void validateKeyFormat(String key) throws SimEngineException {
        if (key.indexOf('_') > -1) {
            throw new SimEngineException(String.format("Invalid key format '%s'", key));
        }
    }

    private void validateExistence(String toCheck) throws SimEngineException {
        if (!basisOf.containsKey(toCheck)) {
            throw new SimEngineException(String.format("Unknown data entry '%s'", toCheck));
        }
    }

    private void validateNotExistence(String toCheck) throws SimEngineException {
        if (basisOf.containsKey(toCheck)) {
            throw new SimEngineException(String.format("Data entry '%s' already exists", toCheck));
        }
    }

    private void validateKind(String op, String toCheck, Kind kindShouldBe) throws SimEngineException {
        if (!kindOf.containsKey(toCheck) || !kindShouldBe.equals(kindOf.get(toCheck))) {
            throw new SimEngineException(String.format("Operation '%s' against a non-%s type '%s'", op, kindShouldBe,
                    toCheck));
        }
    }

    private void validateId(int toCheck) throws SimEngineException {
        if (toCheck < 1) {
            throw new SimEngineException(String.format("Inviad id '%d', should be positive integer", toCheck));
        }
    }

    private void validateProbs(float[] toCheck) throws SimEngineException {
        for (float prob : toCheck) {
            if (prob < 0 || prob > 1) {
                throw new SimEngineException(String.format("Invaid prob '%s', should be between 0 and 1", prob));
            }
        }
    }

    private void validatePairs(int maxIndex, int[] toCheck) throws SimEngineException {
        int len = toCheck.length;
        if (len % 2 != 0) {
            throw new SimEngineException("Sparse vector should be paired");
        }
        for (int offset = 0; offset < len; offset += 2) {
            if (toCheck[offset] < 0 || toCheck[offset] > maxIndex) {
                throw new SimEngineException(String.format("Sparse matrix index '%d' out of bound", toCheck[offset]));
            }
            if (toCheck[offset + 1] < 0) {
                throw new SimEngineException(String.format("Sparse matrix value '%d' should be non-negative",
                        toCheck[offset + 1]));
            }
        }
    }

    private void validateSameBasis(String vkeySource, String vkeyTarget) {
        if (!basisOf.get(vkeySource).equals(basisOf.get(vkeyTarget))) {
            throw new SimEngineException(String.format(
                    "Recommedation[%s, %s] must be between two vector set with same basis", vkeySource, vkeyTarget));
        }
    }

    private String rkey(String vkeySource, String vkeyTarget) {
        return new StringBuilder().append(vkeySource).append("_").append(vkeyTarget).toString();
    }

    public void startCron() {
        final int saveInterval = this.context.getInt("saveinterval");

        Timer cron = new Timer();

        TimerTask savetask = new TimerTask() {
            public void run() {
                save(null);
            }
        };
        cron.schedule(savetask, saveInterval, saveInterval);
    }

    @Override
    public void load(final SimCallback callback) {
        File[] files = new File(savePath).listFiles();
        for (File file : files) {
            String filename = file.getName();
            if (file.isFile() && filename.endsWith("dmp")) {
                bload(null, filename.replaceFirst("[.][^.]+$", ""));
            }
        }

        if (callback != null) {
            callback.ok();
            callback.response();
        }
    }

    @Override
    public void save(final SimCallback callback) {
        for (String bkey : bases.keySet()) {
            bsave(null, bkey);
        }

        if (callback != null) {
            callback.ok();
            callback.response();
        }
    }

    @Override
    public void del(final SimCallback callback, final String key) {
        validateExistence(key);
        writerExecs.get(basisOf.get(key)).execute(new AsyncSafeRunner("del") {
            @Override
            public void invoke() {
                Kind kind = kindOf.get(key);
                switch (kind) {
                case BASIS:
                    for (String vkey : vectorsOf.get(key)) {
                        del(null, vkey);
                    }
                    kindOf.remove(key);
                    basisOf.remove(key);
                    bases.remove(key);
                    writerExecs.remove(key);
                    writerExecs.notifyAll();
                    break;
                case VECTORS:
                    String bkey = basisOf.get(key);
                    for (String target : rtargetsOf.get(key)) {
                        del(null, rkey(key, target));
                    }
                    rtargetsOf.remove(key);
                    for (String source : rsourcesOf.get(key)) {
                        if (!source.equals(key)) {
                            del(null, rkey(source, key));
                        }
                    }
                    rsourcesOf.remove(key);

                    vectorsOf.get(bkey).remove(key);
                    basisOf.remove(key);
                    kindOf.remove(key);
                    bases.get(bkey).vdel(key);
                    break;
                case RECOMM:
                    bases.get(basisOf.get(key)).rdel(key);
                    basisOf.remove(key);
                    kindOf.remove(key);
                    break;
                }
            }
        });

        if (callback != null) {
            callback.ok();
            callback.response();
        }
    }

    @Override
    public void bload(final SimCallback callback, final String bkey) {
        mngmExec.execute(new AsyncSafeRunner("bload") {
            @Override
            public void invoke() {
                validateKeyFormat(bkey);
                String filePath = new StringBuilder(savePath).append(bkey).append(".dmp").toString();
                validatePath(filePath);
                if (basisOf.containsKey(bkey)) {
                    del(null, bkey);
                }
                Basis basis = new Basis(bkey);
                SimBasis simBasis = new SimBasis(context.getSub("basis", bkey), basis);
                bases.put(bkey, simBasis);
                basisOf.put(bkey, bkey);
                kindOf.put(bkey, Kind.BASIS);
                simBasis.addListener(SimEngineImpl.this);

                simBasis.bload(filePath);

                writerExecs.put(bkey, Executors.newSingleThreadExecutor());
            }
        });

        if (callback != null) {
            callback.ok();
            callback.response();
        }
    }

    @Override
    public void bsave(final SimCallback callback, final String bkey) {
        writerExecs.get(bkey).execute(new AsyncSafeRunner("bsave") {
            @Override
            public void invoke() {
                validateKind("bsave", bkey, Kind.BASIS);
                bases.get(bkey).bsave(new StringBuilder(savePath).append(bkey).append(".dmp").toString());
            }
        });

        if (callback != null) {
            callback.ok();
            callback.response();
        }
    }

    @Override
    public void blist(final SimCallback callback) {
        mngmExec.execute(new SafeRunner("blist", callback) {
            @Override
            public void invoke() {
                List<String> bkeys = new ArrayList<String>(bases.keySet());
                Collections.sort(bkeys);
                callback.stringList((String[]) bkeys.toArray(new String[bkeys.size()]));
            }
        });
    }

    @Override
    public void bmk(final SimCallback callback, final String bkey, final String[] base) {
        mngmExec.execute(new SafeRunner("bmk", callback) {
            @Override
            public void invoke() {
                validateKeyFormat(bkey);
                validateNotExistence(bkey);
                Basis basis = new Basis(bkey, base);
                SimBasis simbasis = new SimBasis(context.getSub("basis", bkey), basis);
                bases.put(bkey, simbasis);
                basisOf.put(bkey, bkey);
                kindOf.put(bkey, Kind.BASIS);
                simbasis.addListener(SimEngineImpl.this);
                writerExecs.put(bkey, Executors.newSingleThreadExecutor());

                callback.ok();
            }
        });
    }

    @Override
    public void brev(final SimCallback callback, final String bkey, final String[] base) {
        writerExecs.get(bkey).execute(new SafeRunner("brev", callback) {
            @Override
            public void invoke() {
                validateKind("brev", bkey, Kind.BASIS);
                validateKeyFormat(bkey);
                bases.get(bkey).brev(base);
                callback.ok();
            }
        });
    }

    @Override
    public void bget(final SimCallback callback, final String bkey) {
        validateKind("bget", bkey, Kind.BASIS);
        readerPool.submit(new SafeRunner("bget", callback) {
            @Override
            public void invoke() {
                callback.stringList(bases.get(bkey).bget());
            }
        });
    }

    @Override
    public void vlist(final SimCallback callback, final String bkey) {
        mngmExec.execute(new SafeRunner("vlist", callback) {
            @Override
            public void invoke() {
                validateKind("vlist", bkey, Kind.BASIS);
                List<String> vkeys = vectorsOf.get(bkey);
                if (vkeys == null) {
                    vkeys = new ArrayList<String>();
                } else {
                    Collections.sort(vkeys);
                }
                int i = 0;
                String[] result = new String[vkeys.size()];
                for (String key : vkeys) {
                    result[i++] = key;
                }
                callback.stringList(result);
            }
        });
    }

    @Override
    public void vmk(final SimCallback callback, final String bkey, final String vkey) {
        mngmExec.execute(new SafeRunner("vmk", callback) {
            @Override
            public void invoke() {
                validateKind("vmk", bkey, Kind.BASIS);
                validateKeyFormat(vkey);
                validateNotExistence(vkey);
                bases.get(bkey).vmk(vkey);

                kindOf.put(vkey, Kind.VECTORS);
                basisOf.put(vkey, bkey);
                List<String> vkeys = vectorsOf.get(bkey);
                if (vkeys == null) {
                    vkeys = new ArrayList<String>();
                    vectorsOf.put(bkey, vkeys);
                }
                vkeys.add(vkey);
                callback.ok();
            }
        });
    }

    @Override
    public void vids(final SimCallback callback, final String vkey) {
        validateKind("vget", vkey, Kind.VECTORS);
        final String bkey = basisOf.get(vkey);
        readerPool.submit(new SafeRunner("vids", callback) {
            @Override
            public void invoke() {
                callback.integerList(bases.get(bkey).vids(vkey));
            }
        });
    }

    // CURD operations for one vector in vector-set

    @Override
    public void vget(final SimCallback callback, final String vkey, final int vecid) {
        validateKind("vget", vkey, Kind.VECTORS);
        final String bkey = basisOf.get(vkey);
        readerPool.submit(new SafeRunner("vget", callback) {
            @Override
            public void invoke() {
                callback.floatList(bases.get(bkey).vget(vkey, vecid));
            }
        });
    }

    @Override
    public void vadd(final SimCallback callback, final String vkey, final int vecid, final float[] vector) {
        validateKind("vadd", vkey, Kind.VECTORS);
        validateId(vecid);
        validateProbs(vector);
        final String bkey = basisOf.get(vkey);
        writerExecs.get(bkey).execute(new AsyncSafeRunner("vadd") {
            @Override
            public void invoke() {
                bases.get(bkey).vadd(vkey, vecid, vector);

                if (!counters.containsKey(vkey)) {
                    counters.put(vkey, 0);
                }
                int counter = counters.get(vkey) + 1;
                counters.put(vkey, counter);
                if (counter % bycount == 0) {
                    logger.info(String.format("adding dense vectors %d to %s", counter, vkey));
                }
            }
        });

        callback.ok();
        callback.response();
    }

    @Override
    public void vset(final SimCallback callback, final String vkey, final int vecid, final float[] vector) {
        validateKind("vset", vkey, Kind.VECTORS);
        validateId(vecid);
        validateProbs(vector);
        final String bkey = basisOf.get(vkey);
        writerExecs.get(bkey).execute(new AsyncSafeRunner("vset") {
            @Override
            public void invoke() {
                bases.get(bkey).vset(vkey, vecid, vector);

                if (!counters.containsKey(vkey)) {
                    counters.put(vkey, 0);
                }
                int counter = counters.get(vkey) + 1;
                counters.put(vkey, counter);
                if (counter % bycount == 0) {
                    logger.info(String.format("setting dense vectors %d to %s", counter, vkey));
                }
            }
        });

        callback.ok();
        callback.response();
    }

    @Override
    public void vacc(final SimCallback callback, final String vkey, final int vecid, final float[] vector) {
        validateKind("vacc", vkey, Kind.VECTORS);
        validateId(vecid);
        validateProbs(vector);
        final String bkey = basisOf.get(vkey);
        writerExecs.get(bkey).execute(new AsyncSafeRunner("vacc") {
            @Override
            public void invoke() {
                bases.get(bkey).vacc(vkey, vecid, vector);
            }
        });

        callback.ok();
        callback.response();
    }

    @Override
    public void vrem(final SimCallback callback, final String vkey, final int vecid) {
        this.validateKind("vrem", vkey, Kind.VECTORS);
        final String bkey = basisOf.get(vkey);
        writerExecs.get(bkey).execute(new AsyncSafeRunner("vrem") {
            @Override
            public void invoke() {
                bases.get(bkey).vrem(vkey, vecid);
            }
        });

        callback.ok();
        callback.response();
    }

    // Internal use for client-side sparsification
    @Override
    public void iget(final SimCallback callback, final String vkey, final int vecid) {
        validateExistence(vkey);
        final String bkey = basisOf.get(vkey);
        readerPool.submit(new SafeRunner("iget", callback) {
            @Override
            public void invoke() {
                callback.integerList(bases.get(bkey).iget(vkey, vecid));
            }
        });
    }

    @Override
    public void iadd(SimCallback callback, final String vkey, final int vecid, final int[] pairs) {
        validateKind("iadd", vkey, Kind.VECTORS);
        validateId(vecid);
        final String bkey = basisOf.get(vkey);
        int maxIndex = bases.get(bkey).bget().length;
        validatePairs(maxIndex, pairs);
        writerExecs.get(bkey).execute(new AsyncSafeRunner("iadd") {
            @Override
            public void invoke() {
                bases.get(bkey).iadd(vkey, vecid, pairs);

                if (!counters.containsKey(vkey)) {
                    counters.put(vkey, 0);
                }
                int counter = counters.get(vkey) + 1;
                counters.put(vkey, counter);
                if (counter % bycount == 0) {
                    logger.info(String.format("adding sparse vectors %d to %s", counter, vkey));
                }
            }
        });

        callback.ok();
        callback.response();
    }

    @Override
    public void iset(final SimCallback callback, final String vkey, final int vecid, final int[] pairs) {
        validateKind("iset", vkey, Kind.VECTORS);
        validateId(vecid);
        final String bkey = basisOf.get(vkey);
        int maxIndex = bases.get(bkey).bget().length;
        validatePairs(maxIndex, pairs);
        writerExecs.get(bkey).execute(new AsyncSafeRunner("iset") {
            @Override
            public void invoke() {
                bases.get(bkey).iset(vkey, vecid, pairs);

                if (!counters.containsKey(vkey)) {
                    counters.put(vkey, 0);
                }
                int counter = counters.get(vkey) + 1;
                counters.put(vkey, counter);
                if (counter % bycount == 0) {
                    logger.info(String.format("setting sparse vectors %d to %s", counter, vkey));
                }
            }
        });

        callback.ok();
        callback.response();
    }

    @Override
    public void iacc(final SimCallback callback, final String vkey, final int vecid, final int[] pairs) {
        this.validateKind("iacc", vkey, Kind.VECTORS);
        validateId(vecid);
        final String bkey = basisOf.get(vkey);
        int maxIndex = bases.get(bkey).bget().length;
        validatePairs(maxIndex, pairs);
        writerExecs.get(bkey).execute(new AsyncSafeRunner("iacc") {
            @Override
            public void invoke() {
                bases.get(bkey).iacc(vkey, vecid, pairs);
            }
        });

        callback.ok();
        callback.response();
    }

    @Override
    public void rlist(final SimCallback callback, final String vkey) {
        mngmExec.execute(new SafeRunner("rlist", callback) {
            @Override
            public void invoke() {
                validateKind("rlist", vkey, Kind.VECTORS);
                List<String> targets = new ArrayList<String>();
                Set<String> tgtSet = rtargetsOf.get(vkey);
                if (tgtSet != null) {
                    targets.addAll(tgtSet);
                    Collections.sort(targets);
                }
                callback.stringList((String[]) targets.toArray(new String[targets.size()]));
            }
        });
    }

    @Override
    public void rmk(final SimCallback callback, final String vkeySource, final String vkeyTarget, final String funcscore) {
        mngmExec.execute(new SafeRunner("rmk", callback) {
            @Override
            public void invoke() {
                validateKind("rmk", vkeySource, Kind.VECTORS);
                validateKind("rmk", vkeyTarget, Kind.VECTORS);
                validateSameBasis(vkeyTarget, vkeySource);
                String rkey = rkey(vkeyTarget, vkeySource);
                validateNotExistence(rkey);
                final String bkey = basisOf.get(vkeySource);
                bases.get(bkey).rmk(vkeySource, vkeyTarget, funcscore);
                basisOf.put(rkey, basisOf.get(vkeySource));
                kindOf.put(rkey, Kind.RECOMM);

                if (rtargetsOf.get(vkeySource) == null) {
                    rtargetsOf.put(vkeySource, new HashSet<String>());
                }
                rtargetsOf.get(vkeySource).add(vkeyTarget);

                if (rsourcesOf.get(vkeyTarget) == null) {
                    rsourcesOf.put(vkeyTarget, new HashSet<String>());
                }
                rsourcesOf.get(vkeyTarget).add(vkeySource);

                callback.ok();
            }
        });
    }

    @Override
    public void rget(final SimCallback callback, final String vkeySource, final int vecid, final String vkeyTarget) {
        validateKind("rget", vkeySource, Kind.VECTORS);
        validateKind("rget", vkeyTarget, Kind.VECTORS);
        String rkey = rkey(vkeyTarget, vkeySource);
        validateExistence(rkey);
        final String bkey = basisOf.get(vkeySource);
        readerPool.submit(new SafeRunner("rget", callback) {
            @Override
            public void invoke() {
                callback.stringList(bases.get(bkey).rget(vkeySource, vecid, vkeyTarget));
            }
        });
    }

    @Override
    public void rrec(final SimCallback callback, final String vkeySource, final int vecid, final String vkeyTarget) {
        validateKind("rget", vkeySource, Kind.VECTORS);
        validateKind("rget", vkeyTarget, Kind.VECTORS);
        String rkey = rkey(vkeyTarget, vkeySource);
        validateExistence(rkey);
        final String bkey = basisOf.get(vkeySource);
        readerPool.submit(new SafeRunner("rrec", callback) {
            @Override
            public void invoke() {
                callback.integerList(bases.get(bkey).rrec(vkeySource, vecid, vkeyTarget));
            }
        });
    }

    @Override
    public void listen(final String bkey, final BasisListener listener) {
        writerExecs.get(bkey).execute(new Runnable() {
            @Override
            public void run() {
                bases.get(bkey).addListener(listener);
            }
        });
    }

    @Override
    public void listen(final String vkey, final VectorSetListener listener) {
        final String bkey = basisOf.get(vkey);
        writerExecs.get(bkey).execute(new Runnable() {
            @Override
            public void run() {
                bases.get(bkey).addListener(vkey, listener);
            }
        });
    }

    @Override
    public void listen(final String srcVkey, final String tgtVkey, final RecommendationListener listener) {
        final String bkey = basisOf.get(srcVkey);
        writerExecs.get(bkey).execute(new Runnable() {
            @Override
            public void run() {
                bases.get(bkey).addListener(srcVkey, tgtVkey, listener);
            }
        });
    }

    @Override
    public void onVecSetAdded(String bkeySrc, String vkey) {
        basisOf.put(vkey, bkeySrc);

        List<String> vecs = vectorsOf.get(bkeySrc);
        if (vecs == null) {
            vecs = new ArrayList<String>();
            vectorsOf.put(bkeySrc, vecs);
        }
        vecs.add(vkey);

        kindOf.put(vkey, Kind.VECTORS);
    }

    @Override
    public void onVecSetDeleted(String bkeySrc, String vkey) {
        basisOf.remove(vkey);
        vectorsOf.get(bkeySrc).remove(vkey);
        kindOf.remove(vkey);
    }

    @Override
    public void onRecAdded(String bkeySrc, String vkeyFrom, String vkeyTo) {
        String rkey = rkey(vkeyFrom, vkeyTo);

        basisOf.put(rkey, bkeySrc);

        Set<String> tgt = rtargetsOf.get(vkeyFrom);
        if (tgt == null) {
            tgt = new HashSet<String>();
            rtargetsOf.put(vkeyFrom, tgt);
        }
        tgt.add(vkeyTo);

        kindOf.put(rkey, Kind.RECOMM);
    }

    @Override
    public void onRecDeleted(String bkeySrc, String vkeyFrom, String vkeyTo) {
        String rkey = rkey(vkeyFrom, vkeyTo);
        basisOf.remove(rkey);
        rtargetsOf.get(vkeyFrom).remove(vkeyTo);
        kindOf.remove(rkey);
    }

}
