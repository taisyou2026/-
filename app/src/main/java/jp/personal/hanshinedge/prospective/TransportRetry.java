package jp.personal.hanshinedge.prospective;

import org.json.*;
import java.io.IOException;
import java.net.*;
import java.time.Instant;
import java.util.concurrent.Callable;

/** Bounded transport-only retry. It never retries parser, HTTP, TLS or policy failures. */
final class TransportRetry {
    static final int MAX_ATTEMPTS = 3;
    static final long[] BACKOFF_MS = {2000, 5000};
    interface Sleeper { void sleep(long ms) throws InterruptedException; }
    static final class Result<T> {
        final T value; final JSONObject evidence;
        Result(T value, JSONObject evidence) { this.value=value; this.evidence=evidence; }
    }
    static final class Failure extends IOException {
        final JSONObject evidence;
        Failure(Exception cause, JSONObject evidence) {
            super("SOURCE_UNAVAILABLE: "+cause.getClass().getSimpleName()+": "+cause.getMessage(),cause);
            this.evidence=evidence;
        }
    }
    static boolean retryable(Exception e) {
        return e instanceof UnknownHostException || e instanceof SocketTimeoutException || e instanceof ConnectException;
    }
    static <T> Result<T> run(Callable<T> operation, Sleeper sleeper) throws Exception {
        JSONArray errors=new JSONArray(),details=new JSONArray();
        JSONObject evidence=new JSONObject().put("transport_attempts",0).put("transport_errors",errors)
            .put("transport_error_details",details).put("final_transport_status","NOT_VERIFIED");
        for(int n=1;n<=MAX_ATTEMPTS;n++) {
            evidence.put("transport_attempts",n);
            try {
                T value=operation.call();
                return new Result<>(value,evidence.put("final_transport_status","PASS"));
            } catch(Exception error) {
                errors.put(error.getClass().getSimpleName());
                details.put(new JSONObject().put("attempt",n).put("error",error.getClass().getSimpleName())
                    .put("message",error.getMessage()).put("failed_at",Instant.now().toString()));
                if(!retryable(error) || n==MAX_ATTEMPTS) {
                    evidence.put("final_transport_status",retryable(error)?"SOURCE_UNAVAILABLE":"NON_RETRYABLE_FAILURE");
                    throw new Failure(error,evidence);
                }
                try { sleeper.sleep(BACKOFF_MS[n-1]); }
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    evidence.put("final_transport_status","INTERRUPTED");
                    throw new Failure(interrupted,evidence);
                }
            }
        }
        throw new IllegalStateException("UNREACHABLE_TRANSPORT_STATE");
    }
}
