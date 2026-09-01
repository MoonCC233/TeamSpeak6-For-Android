const crypto = require('crypto');

function deriveRoomId(serverUid, channelId) {
  const input = `${serverUid || ''}|${channelId ?? 0}`;
  return crypto.createHash('sha256').update(input, 'utf8').digest('hex').slice(0, 32);
}

module.exports = { deriveRoomId };
