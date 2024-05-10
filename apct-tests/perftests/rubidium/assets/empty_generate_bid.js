"use strict";

function generateBid(ad) {
  let input = ad.metadata.input;

  return {
    ad: 'example',
    bid: input,
    render: ad.renderUrl
  }
}