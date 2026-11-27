// Experiment metadata only. No access to Android checkpoints or personal files.
import { createHash, randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync, renameSync, rmSync } from "node:fs";
import { join } from "node:path";
const hash = text => createHash("sha256").update(text).digest("hex");
function id(value) {
  if (typeof value !== "string" || !/^[a-zA-Z0-9_-]{1,48}$/.test(value)) throw Error("Bad version");
  return value;
}
export class LabVersions {
  constructor(directory) { this.dir=directory; mkdirSync(directory,{recursive:true}); }
  write(name, value) {
    const path = join(this.dir,name), tmp = path+"."+randomUUID()+".tmp";
    try { writeFileSync(tmp,value,{mode:0o600,flag:"wx"}); renameSync(tmp,path); }
    finally { rmSync(tmp,{force:true}); }
  }
  save(version, report) {
    id(version);
    const payload=JSON.stringify({schema:1,version,report});
    writeFileSync(join(this.dir,version+".json"),JSON.stringify({payload,sha256:hash(payload)}),{mode:0o600,flag:"wx"});
  }
  read(version) {
    const row=JSON.parse(readFileSync(join(this.dir,id(version)+".json"),"utf8"));
    if (hash(row.payload)!==row.sha256) throw Error("Checkpoint checksum mismatch");
    const value=JSON.parse(row.payload);
    if (value.schema!==1 || value.version!==version) throw Error("Checkpoint schema mismatch");
    return value;
  }
  current() {
    try { return JSON.parse(readFileSync(join(this.dir,"active.json"),"utf8")); }
    catch(e) { if(e.code==="ENOENT") return null; throw e; }
  }
  activate(version) {
    const next=this.read(version);
    if(next.report.regression !== false) throw Error("Candidate has not passed synthetic quality gate");
    const previous=this.current();
    this.write("active.json", JSON.stringify({active:version, previous:previous?.active??null}));
  }
  rollback() {
    const current=this.current();
    if (!current?.previous) throw Error("No previous version");
    const previous=this.read(current.previous);
    if(previous.report.regression !== false) throw Error("Previous checkpoint rejected");
    this.write("active.json",JSON.stringify({active:current.previous,previous:current.active}));
  }
}
