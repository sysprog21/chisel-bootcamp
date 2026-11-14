"""Run from the repository root: python3 tests/test_visualizer.py."""

from kernel import scala_kernel

with scala_kernel(timeout=180) as run:
    run('interp.load.module(os.pwd / "source" / "load-ivy.sc")')
    # The DOT text of an edge and of a node's attributes: every id is a quoted FIRRTL reference
    run('''def q(id: String) = "\\"" + id + "\\""
def edge(a: String, b: String) = q(a) + " -> " + q(b)
def node(n: String) = q(n) + " ["''')
    run('''val graph = firrtlToDot("""circuit Top :
  module Child :
    input other : UInt<1>
    connect io_out, other
  public module Top :
    input sel : UInt<1>
    input nested : UInt<1>
    input data : UInt<1>
    output out : UInt<1>
    output elseout : UInt<1>
    when sel :
      when nested :
        connect out, data
    else :
      connect elseout, UInt<1>(0)
""")
assert(graph.contains(edge("sel", "out") + " [style=dashed"))
assert(graph.contains(edge("nested", "out") + " [style=dashed"))
assert(graph.contains(edge("sel", "elseout") + " [style=dashed"))
assert(graph.contains(edge("data", "out")))
assert(!graph.contains("other"))
assert(graph.contains("Module: Top"))''')
    run('''val constOnly = firrtlToDot("""circuit K :
  public module K :
    input clock : Clock
    output k : UInt<1>
    connect k, UInt<1>(1)
""")
assert(constOnly.contains(node("k")))
assert(!constOnly.contains(node("clock")))''')
    run('''val hierarchy = firrtlToDot("""circuit Top :
  module Child :
    input in : UInt<8>
    output out : UInt<8>
    connect out, in
  public module Top :
    input data : UInt<8>
    output result : UInt<8>
    inst a of Child
    inst b of Child
    connect a.in, data
    connect b.in, a.out
    connect result, b.out
""")
assert(hierarchy.contains(edge("data", "a.in")))
assert(hierarchy.contains(edge("a.in", "a.out")))
assert(hierarchy.contains(edge("a.out", "b.in")))
assert(hierarchy.contains(edge("b.in", "b.out")))
assert(hierarchy.contains(edge("b.out", "result")))''')
    run('''val indexed = firrtlToDot("""circuit VecTest :
  public module VecTest :
    wire values : UInt<8>[4]
    input index : UInt<2>
    output result : UInt<8>
    connect result, values[index]
    wire storage : UInt<8>[4]
    connect storage[index], result
    connect result, storage[0]
    connect values[1], result
""")
assert(indexed.contains(edge("values", "values[index]")))
assert(indexed.contains(edge("index", "values[index]")))
assert(indexed.contains(edge("values[index]", "result")))
assert(indexed.contains(edge("result", "storage[index]")))
assert(indexed.contains(edge("index", "storage[index]")))
assert(indexed.contains(edge("storage[index]", "storage")))''')
    run("""assert(indexed.contains(edge("storage", "storage[0]")))
assert(indexed.contains(edge("storage[0]", "result")))
assert(indexed.contains(edge("values[1]", "values")))""")
    run('''val nested = firrtlToDot("""circuit N :
  public module N :
    output io : { flip bus : { data : UInt<8> }, out : UInt<8> }
    connect io.out, io.bus.data
""")
// shapes, and Okabe-Ito fills: inputs blue circles, outputs orange double circles
assert(nested.contains(node("io.bus.data") + "shape=circle, fillcolor=\\"#9ccfee\\"]"))
assert(nested.contains(node("io.out") + "shape=doublecircle, fillcolor=\\"#f3c766\\"]"))''')
    run('''val withReg = firrtlToDot("""circuit R :
  public module R :
    input clock : Clock
    input d : UInt<8>
    output q : UInt<8>
    reg z : UInt<8>[2], clock
    reg y : UInt<8>, clock
    connect z[0], d
    connect y, z[0]
    connect q, y
""")
// registers, and their elements: reddish-purple 3D boxes
assert(withReg.contains(node("z[0]") + "shape=box3d, fillcolor=\\"#e3b3cf\\"]"))
assert(withReg.contains(node("y") + "shape=box3d, fillcolor=\\"#e3b3cf\\"]"))''')
    # #18: references that sanitizing to `_` made equal stay different nodes
    run('''val distinct = firrtlToDot("""circuit Top :
  module Child :
    input in : UInt<8>
    output out : UInt<8>
    connect out, in
  public module Top :
    input data : UInt<8>
    input a_in : UInt<8>
    input v_0 : UInt<8>
    output result : UInt<8>
    output other : UInt<8>
    output io : { a_b : UInt<8>, a : { b : UInt<8> } }
    wire v : UInt<8>[2]
    inst a of Child
    connect a.in, data
    connect result, a_in
    connect v[0], v_0
    connect other, v[0]
    connect io.a_b, data
    connect io.a.b, a_in
""")
assert(distinct.contains(edge("data", "a.in")))
assert(distinct.contains(edge("a_in", "result")))
assert(!distinct.contains(edge("data", "a_in"))) // what merging `a.in` with `a_in` drew
assert(distinct.contains(edge("v_0", "v[0]")) && distinct.contains(edge("v[0]", "other")))
assert(distinct.contains(edge("data", "io.a_b")) && distinct.contains(edge("a_in", "io.a.b")))''')
    run('''val vecPort = firrtlToDot("""circuit V :
  module Child :
    input i : UInt<1>
    input v : UInt<8>[2]
    output o : UInt<8>
    connect o, v[i]
  public module V :
    input i : UInt<1>
    output io : { v : { flip a : UInt<1> }[2] }
    output r : UInt<8>
    inst c of Child
    connect c.v[i], io.v[0].a
    connect r, c.o
""")
// an element of a port's vector of bundles is that port's field
assert(vecPort.contains(node("io.v[0].a") + "shape=circle"))
// the child's `v[i]` selects with the child's `i`, not the top's
assert(vecPort.contains(edge("c.i", "c.v[c.i]")) && vecPort.contains(edge("i", "c.v[i]")))
assert(!vecPort.contains(edge("i", "c.v[c.i]")))''')
    # nested and chained dynamic selections, as Chisel 7 emits them
    run('''val nestedIndex = firrtlToDot("""circuit Nested :
  public module Nested :
    input idx : UInt<1>[2]
    input v : UInt<8>[2]
    input w : UInt<8>[2][2]
    input i : UInt<1>
    input j : UInt<1>
    input x : UInt<8>
    output out : UInt<8>
    output o2 : UInt<8>
    wire s : UInt<8>[2][2]
    connect out, v[idx[0]]
    connect o2, w[i][j]
    connect s[i][j], x
""")
assert(nestedIndex.contains(edge("v", "v[idx[0]]")) && nestedIndex.contains(edge("idx[0]", "v[idx[0]]")))
assert(nestedIndex.contains(edge("v[idx[0]]", "out")))
assert(nestedIndex.contains(node("idx[0]") + "shape=circle"))
assert(nestedIndex.contains(edge("w", "w[i]")) && nestedIndex.contains(edge("i", "w[i]")))
assert(nestedIndex.contains(edge("w[i]", "w[i][j]")) && nestedIndex.contains(edge("j", "w[i][j]")))
assert(nestedIndex.contains(edge("w[i][j]", "o2")) && !nestedIndex.contains(edge("w", "w[i][j]")))
assert(nestedIndex.contains(edge("x", "s[i][j]")) && nestedIndex.contains(edge("s[i][j]", "s[i]")))
assert(nestedIndex.contains(edge("s[i]", "s")))''')
    run('''val mixedIndex = firrtlToDot("""circuit Mixed :
  module Child :
    input idx : UInt<1>[2]
    input v : UInt<8>[2]
    output o : UInt<8>
    connect o, v[idx[0]]
  public module Mixed :
    input w : UInt<8>[2][2]
    input i : UInt<1>
    input j : UInt<1>
    input x : UInt<8>
    output o : UInt<8>
    output p : UInt<8>
    output q : UInt<8>
    wire m : UInt<8>[2][2]
    inst c of Child
    connect o, w[i][0]
    connect m[0][1], x
    connect p, m[i][j]
    when w[i][j] :
      connect q, c.o
""")
// a fixed selection of a dynamic one stays joined to it
assert(mixedIndex.contains(edge("w[i]", "w[i][0]")) && mixedIndex.contains(edge("w[i][0]", "o")))
// a fixed write reaches a dynamic read of the same nested vector
assert(mixedIndex.contains(edge("m[0][1]", "m[0]")) && mixedIndex.contains(edge("m[0]", "m")))
assert(mixedIndex.contains(edge("m", "m[i]")) && mixedIndex.contains(edge("m[i][j]", "p")))
// the prefix reaches nested indices inside an instance
assert(mixedIndex.contains(edge("c.idx[0]", "c.v[c.idx[0]]")) && mixedIndex.contains(edge("c.v", "c.v[c.idx[0]]")))
// a nested reference as a when condition
assert(mixedIndex.contains(edge("w[i][j]", "q") + " [style=dashed"))''')
    # bulk connects: a whole vector or bundle and its elements or fields
    run(
        '''val bulk = firrtlToDot("""circuit B :
  public module B :
    input a : UInt<8>
    input b : UInt<8>
    input x : UInt<8>
    input y : UInt<8>[2]
    output out : UInt<8>[2]
    output o : { a : UInt<8> }
    output e : UInt<8>
    input next : { valid : UInt<1> }
    output f : UInt<8>
    input io : { sel : UInt<1> }
    output g : UInt<8>
    wire w : UInt<8>[2]
    wire r : { a : UInt<8> }
    wire z : UInt<8>[2]
    connect w[0], a
    connect w[1], b
    connect out, w
    connect r.a, x
    connect o, r
    connect z, y
    connect e, z[1]
    wire state : { valid : UInt<1> }
    connect state, next
    when state.valid :
      connect f, a
    wire s : { sel : UInt<1> }
    connect s, io
    connect g, y[s.sel]
""")
assert(bulk.contains(edge("w[0]", "w")) && bulk.contains(edge("w[1]", "w")) && bulk.contains(edge("w", "out")))
assert(bulk.contains(edge("r.a", "r")) && bulk.contains(edge("r", "o")))
assert(bulk.contains(edge("z", "z[1]")) && bulk.contains(edge("z[1]", "e")))
// a part read only by a when condition
assert(bulk.contains(edge("next", "state")) && bulk.contains(edge("state", "state.valid")))
assert(bulk.contains(edge("state.valid", "f") + " [style=dashed"))
// a port's fields: an output read from outside, an input written there
// a part read only as an index
assert(bulk.contains(edge("s", "s.sel")) && bulk.contains(edge("s.sel", "y[s.sel]")))
assert(bulk.contains(edge("o", "o.a")) && bulk.contains(edge("next.valid", "next")))
// parts are not joined to each other, nor a part to the whole when nothing flows that way
assert(!bulk.contains(edge("w[0]", "w[1]")) && !bulk.contains(edge("w", "w[0]")) && !bulk.contains(edge("z[1]", "z")))'''
    )
    # #18: DOT keywords are valid node names once quoted
    run('''val keywords = firrtlToDot("""circuit K :
  public module K :
    input node : UInt<1>
    output edge : UInt<1>
    connect edge, node
""")
assert(keywords.contains(edge("node", "edge")))''')
    # #19: a name that only starts with a port's name is not that port
    run('''val prefixed = firrtlToDot("""circuit P :
  public module P :
    input foo : UInt<8>
    output o : UInt<8>
    node foo_tmp = not(foo)
    connect o, foo_tmp
""")
assert(prefixed.contains(node("foo") + "shape=circle"))
assert(prefixed.contains(node("foo_tmp") + "shape=ellipse"))''')
    # every graph is valid DOT
    run(
        """Seq(graph, constOnly, hierarchy, indexed, nested, withReg, distinct, vecPort, nestedIndex, mixedIndex, bulk, keywords, prefixed).foreach { g =>
  val r = os.proc("dot", "-Tsvg").call(stdin = g, check = false, stderr = os.Pipe)
  assert(r.exitCode == 0 && r.err.text().isEmpty, r.err.text() + g)
}"""
    )
    print("visualizer regression checks passed")
