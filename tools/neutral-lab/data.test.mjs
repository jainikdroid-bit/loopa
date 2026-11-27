import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { metrics } from "./metrics.mjs";
const fixture=JSON.parse(readFileSync(new URL("./data/synthetic-v2.json",import.meta.url),"utf8"));
const ids=new Set(fixture.documents.map(d=>d.id));
test("neutral data is internally consistent",()=>{
  assert.equal(fixture.schema,1);
  assert.equal(ids.size,72);
  assert.equal(fixture.queries.length,36);
  assert.equal(fixture.tag_clouds.length,12);
  for(const q of fixture.queries){
    assert.equal(Object.keys(q.grades).length,6);
    for(const [id,g] of Object.entries(q.grades)){
      assert.ok(ids.has(id)); assert.ok(g>=1&&g<=3);
    }
  }
  for(const d of fixture.documents){
    assert.equal(d.source,"synthetic");
    assert.equal(d.tags.length,3);
    assert.equal(new Set(d.tags).size,3);
    assert.ok(fixture.tag_clouds.some(c=>c.topic===d.topic));
  }
});
test("three language labels share identical relevance judgments",()=>{
  for(const cloud of fixture.tag_clouds){
    const q=fixture.queries.filter(q=>q.id.startsWith(cloud.topic+"-"));
    assert.deepEqual(q.map(x=>x.language).sort(),["en","pl","ru"]);
    assert.deepEqual(q[0].grades,q[1].grades);
    assert.deepEqual(q[0].grades,q[2].grades);
  }
});
test("ideal ordering evaluates correctly for every synthetic query",()=>{
  for(const q of fixture.queries){
    const order=Object.keys(q.grades).sort((a,b)=>q.grades[b]-q.grades[a]);
    const m=metrics(order,q.grades,6);
    assert.equal(m.precision,1); assert.equal(m.recall,1); assert.equal(m.ndcg,1);
  }
});
