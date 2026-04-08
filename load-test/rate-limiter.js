import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const TARGET_URL = 'http://localhost:8080/api/v1/users/anything';

// Must match gateway.jwt.secret in application.yml 
const JWT_SECRET_B64 = 'bXlTdXBlclNlY3JldEtleUZvckpXVEF1dGhUaGlzSXNBVGVzdA==';

// All VUs share one userId so they drain the same 100-token bucket together
const SHARED_USER_ID = 'load-test-user';


const successCount    = new Counter('requests_ok');
const rateLimitCount  = new Counter('requests_rate_limited');
const rateLimitedRate = new Rate('rate_limited_rate');
const allowedLatency  = new Trend('latency_allowed_ms', true);
const rejectedLatency = new Trend('latency_rejected_ms', true);

export const options = {
    stages: [
        { duration: '30s', target: 50 }, // ramp up
        { duration: '30s', target: 50 }, // sustain
        { duration: '10s', target: 0  }, // ramp down
    ],

    thresholds: {
        'rate_limited_rate': ['rate > 0.5'],
        'http_req_duration{status:429}': ['p(95) < 50'],
        'http_req_duration{status:200}': ['p(95) < 500'],
        'checks': ['rate == 1.0'],
    },
};


export function setup() {
    const token = mintJwt(SHARED_USER_ID);

    // Smoke-test the gateway before the full ramp begins.
    const probe = http.get(TARGET_URL, { headers: { Authorization: `Bearer ${token}` } });
    if (probe.status !== 200 && probe.status !== 429) {
        throw new Error(`Gateway unreachable or returned unexpected status ${probe.status}. Aborting.`);
    }

    return { token };
}


export default function (data) {
    const res = http.get(TARGET_URL, {
        headers: { Authorization: `Bearer ${data.token}` },
        // Fail fast rather than hanging on a down gateway.
        timeout: '5s',
    });

    const ok          = res.status === 200;
    const rateLimited = res.status === 429;

    check(res, {
        'response is 200 or 429': (r) => r.status === 200 || r.status === 429,
        '429 includes X-RateLimit-Remaining header': (r) =>
            r.status !== 429 || r.headers['X-Ratelimit-Remaining'] !== undefined,
    });

    if (ok) {
        successCount.add(1);
        allowedLatency.add(res.timings.duration);
    }

    if (rateLimited) {
        rateLimitCount.add(1);
        rejectedLatency.add(res.timings.duration);
    }

    rateLimitedRate.add(rateLimited ? 1 : 0);

    // No sleep — sustained maximum pressure is the point of this test.
    // The token bucket (100 req/min) will saturate almost immediately once
    // all 50 VUs are active, producing a high 429 rate.
}


export function teardown(data) {
    console.log('Load test complete.');
    console.log(`JWT used: ${data.token.substring(0, 40)}...`);
}


/**
 * Mints a signed HS256 JWT identical to what the gateway's JwtUtil produces.
 *
 */
function mintJwt(subject) {
    const now = Math.floor(Date.now() / 1000);

    const header  = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const payload = b64url(JSON.stringify({ sub: subject, iat: now, exp: now + 3600 }));
    const signingInput = `${header}.${payload}`;

    // Decode the base64 secret to raw bytes
    const rawKey    = encoding.b64decode(JWT_SECRET_B64, 'std');
    const signature = crypto.hmac('sha256', rawKey, signingInput, 'base64rawurl');

    return `${signingInput}.${signature}`;
}

function b64url(str) {
    return encoding.b64encode(str, 'rawurl');
}
