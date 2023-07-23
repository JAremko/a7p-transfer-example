self.onmessage = function(event) {
  const data = event.data;

  // Perform transformation here. For now, we're using an identity function
  const result = data;

  // Send the transformed data back to the main thread
  self.postMessage(result);
};

