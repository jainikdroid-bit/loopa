import { performance } from "node:perf_hooks";
import { compare } from "./metrics.mjs";
import { baseline,candidate,cases } from "./fixture.mjs";
const start=performance.now();
const report=compare(baseline,candidate,cases,3);
console.log(JSON.stringify({ fixture:"synthetic neutral rankings, not a trained model", ...report,
  elapsed_ms:performance.now()-start, heap_bytes:process.memoryUsage().heapUsed },null,2));
if(report.regression) process.exitCode=1;
