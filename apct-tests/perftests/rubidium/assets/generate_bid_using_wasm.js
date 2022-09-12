function generateBid(ad, wasmModule) {
  let input = ad.metadata.input;

  const instance = new WebAssembly.Instance(wasmModule);

  const memory = instance.exports.memory;
  const input_in_memory = new Float32Array(memory.buffer, 0, 200);
  for (let i = 0; i < input.length; ++i) {
    input_in_memory[i] = input[i];
  }
  const results = [
    instance.exports.nn_forward_model0(input_in_memory.length, input_in_memory),
    instance.exports.nn_forward_model1(input_in_memory.length, input_in_memory),
    instance.exports.nn_forward_model2(input_in_memory.length, input_in_memory),
    instance.exports.nn_forward_model3(input_in_memory.length, input_in_memory),
    instance.exports.nn_forward_model4(input_in_memory.length, input_in_memory),
  ];
  const bid = results.map(x => Math.max(x, 1)).reduce((x, y) => x * y);
  return {
    ad: 'example',
    bid: bid,
    render: ad.renderUrl
  }
}