import { test } from "node:test";
import assert from "node:assert/strict";
import { metrics,compare } from "./metrics.mjs";
import { baseline,candidate,cases } from "./fixture.mjs";
test("ideal ranking",()=>assert.deepEqual(metrics(["a","b"],{a:3,b:1},2),{precision:1,recall:1,ndcg:1}));
test("duplicates rejected",()=>assert.throws(()=>metrics(["a","a"],{a:1},2)));
test("empty truth has no recall or ndcg",()=>assert.deepEqual(metrics([],{},2),{precision:0,recall:null,ndcg:null}));
test("regression gate catches reversed fixture",()=>{
  assert.equal(compare(baseline,candidate,cases,3).regression,false);
  assert.equal(compare(candidate,baseline,cases,3).regression,true);
});
