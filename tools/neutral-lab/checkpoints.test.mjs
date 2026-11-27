import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync,rmSync,writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { LabVersions } from "./checkpoints.mjs";
test("experimental activation, rollback, regression and corruption",()=>{
  const directory=mkdtempSync(join(tmpdir(),"neutral-lab-"));
  try {
    const versions=new LabVersions(directory);
    versions.save("a",{regression:false});
    versions.save("b",{regression:false});
    versions.save("bad",{regression:true});
    versions.activate("a"); versions.activate("b");
    assert.equal(versions.current().active,"b");
    assert.throws(()=>versions.activate("bad"));
    assert.equal(versions.current().active,"b");
    versions.rollback(); assert.equal(versions.current().active,"a");
    writeFileSync(join(directory,"b.json"),"{}");
    assert.throws(()=>versions.activate("b"));
    assert.equal(versions.current().active,"a");
  } finally { rmSync(directory,{recursive:true,force:true}); }
});
