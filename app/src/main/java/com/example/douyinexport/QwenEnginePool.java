package com.example.douyinexport;

import java.util.concurrent.*;

/**
 * One idle recognizer per app process, with a two-minute idle timeout.
 * No concurrent recognizers are retained. Native allocations are released
 * after idle timeout, memory pressure or a change of CPU thread count.
 */
public final class QwenEnginePool {
    private static final Object LOCK=new Object();
    private static final ScheduledExecutorService SCHEDULER=
        Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"qwen3-cache-expiry");
            t.setDaemon(true);
            return t;
        });
    private static final long IDLE_SECONDS=120;
    private static Qwen3AsrEngine cached;
    private static String cachedPath="";
    private static int cachedThreads=0;
    private static ScheduledFuture<?> expiry;

    private QwenEnginePool(){}

    public static final class Lease implements AutoCloseable {
        public final Qwen3AsrEngine engine;
        public final boolean reused;
        private final String path;
        private final int threads;
        private boolean returned=false;
        private Lease(Qwen3AsrEngine engine,boolean reused,String path,int threads){
            this.engine=engine;this.reused=reused;this.path=path;this.threads=threads;
        }
        @Override public void close(){
            synchronized(LOCK){
                if(returned)return;
                returned=true;
            }
            QwenEnginePool.putBack(this);
        }
    }

    public static Lease obtain(String path,int threads) {
        Qwen3AsrEngine discard=null;
        synchronized(LOCK){
            if(expiry!=null){expiry.cancel(false);expiry=null;}
            if(cached!=null){
                if(cachedThreads==threads&&cachedPath.equals(path)){
                    Qwen3AsrEngine engine=cached;
                    cached=null;
                    return new Lease(engine,true,path,threads);
                }
                discard=cached;cached=null;cachedPath="";cachedThreads=0;
            }
        }
        if(discard!=null)discard.close();
        return new Lease(new Qwen3AsrEngine(path,threads),false,path,threads);
    }

    private static void putBack(Lease lease){
        Qwen3AsrEngine discard=null;
        synchronized(LOCK){
            if(cached!=null){discard=cached;cached=null;}
            cached=lease.engine;cachedThreads=lease.threads;cachedPath=lease.path;
            if(expiry!=null)expiry.cancel(false);
            expiry=SCHEDULER.schedule(QwenEnginePool::clearIdle,IDLE_SECONDS,TimeUnit.SECONDS);
        }
        if(discard!=null)discard.close();
    }

    public static void clearIdle(){
        Qwen3AsrEngine toClose=null;
        synchronized(LOCK){
            if(expiry!=null){expiry.cancel(false);expiry=null;}
            if(cached!=null){
                toClose=cached;cached=null;cachedPath="";cachedThreads=0;
            }
        }
        if(toClose!=null)toClose.close();
    }
}
