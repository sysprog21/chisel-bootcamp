"""Run from the repository root: python3 tests/test_visualizer.py."""
from jupyter_client.manager import start_new_kernel
km,kc=start_new_kernel(kernel_name='scala',startup_timeout=300)
def run(code):
    mid=kc.execute(code)
    while True:
        m=kc.get_iopub_msg(timeout=180)
        if m['parent_header'].get('msg_id') != mid: continue
        if m['msg_type']=='stream': print(m['content']['text'], end='')
        if m['msg_type']=='error': raise RuntimeError(m['content'])
        if m['msg_type']=='status' and m['content']['execution_state']=='idle': break
try:
    run('interp.load.module(os.pwd / "source" / "load-ivy.sc")')
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
assert(graph.contains("sel -> out [style=dashed"))
assert(graph.contains("nested -> out [style=dashed"))
assert(graph.contains("sel -> elseout [style=dashed"))
assert(graph.contains("data -> out"))
assert(!graph.contains("other"))
assert(graph.contains("Module: Top"))''')
    run('''val constOnly = firrtlToDot("""circuit K :
  public module K :
    input clock : Clock
    output k : UInt<1>
    connect k, UInt<1>(1)
""")
assert(constOnly.contains("k ["))
assert(!constOnly.contains("clock ["))''')
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
assert(hierarchy.contains("data -> a_in"))
assert(hierarchy.contains("a_in -> a_out"))
assert(hierarchy.contains("a_out -> b_in"))
assert(hierarchy.contains("b_in -> b_out"))
assert(hierarchy.contains("b_out -> result"))''')
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
assert(indexed.contains("values -> values_index"))
assert(indexed.contains("index -> values_index"))
assert(indexed.contains("values_index -> result"))
assert(indexed.contains("result -> storage_index"))
assert(indexed.contains("index -> storage_index"))
assert(indexed.contains("storage_index -> storage"))''')
    run('''assert(indexed.contains("storage -> storage_0"))
assert(indexed.contains("storage_0 -> result"))
assert(indexed.contains("values_1 -> values"))''')
    run('''val nested = firrtlToDot("""circuit N :
  public module N :
    output io : { flip bus : { data : UInt<8> }, out : UInt<8> }
    connect io.out, io.bus.data
""")
assert(nested.contains("io_bus_data [shape=circle"))
assert(nested.contains("io_out [shape=doublecircle"))''')
    print('visualizer regression checks passed')
finally:
    kc.stop_channels(); km.shutdown_kernel(now=True)
