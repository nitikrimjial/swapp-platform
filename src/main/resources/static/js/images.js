// Client-side image preparation: downscale and re-encode photos before upload.
// Keeps uploads fast and storage small (a 6 MB phone photo typically becomes ~250 KB).

export const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'];
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;   // server limit per image
const MAX_INPUT_BYTES = 30 * 1024 * 1024;          // what we accept before compression
const MAX_DIMENSION = 1600;
const QUALITY = 0.84;

function loadImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('unreadable')); };
    img.src = url;
  });
}

/**
 * Returns a File ready to upload, or throws an Error with a user-friendly message.
 * GIFs are passed through untouched (to keep animation); other formats are resized and saved as JPEG.
 */
export async function prepareImage(file, maxDimension = MAX_DIMENSION) {
  if (!ACCEPTED_TYPES.includes(file.type)) throw new Error(`${file.name}: unsupported format`);
  if (file.size > MAX_INPUT_BYTES) throw new Error(`${file.name}: file is too large`);
  if (file.type === 'image/gif') {
    if (file.size > MAX_UPLOAD_BYTES) throw new Error(`${file.name}: GIFs must be 5 MB or smaller`);
    return file;
  }
  let img;
  try {
    img = await loadImage(file);
  } catch {
    throw new Error(`${file.name}: could not read this image`);
  }
  const scale = Math.min(1, maxDimension / Math.max(img.naturalWidth, img.naturalHeight));
  // Always re-encode, even small images: drawing to a canvas drops embedded metadata such as the GPS position
  // a phone camera records, which could reveal where the member lives.
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(img.naturalWidth * scale);
  canvas.height = Math.round(img.naturalHeight * scale);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#ffffff'; // flatten transparency for JPEG
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', QUALITY));
  if (!blob) throw new Error(`${file.name}: could not process this image`);
  // Never fall back to the original file, even if it was smaller: it may still carry location metadata.
  const result = new File([blob], file.name.replace(/\.\w+$/, '') + '.jpg', { type: 'image/jpeg' });
  if (result.size > MAX_UPLOAD_BYTES) throw new Error(`${file.name}: still too large after compression`);
  return result;
}
