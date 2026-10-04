/** 按最长边缩放并压到体积上限之内。失败时返回原文件，绝不卡住 */
export function compressImage(file, {maxEdge = 1600, maxBytes = 850 * 1024} = {}) {
    // GIF 不能过 canvas：canvas 只画第一帧，动图会被压成一张静帧
    if (file.type === 'image/gif') {
        return Promise.resolve(file)
    }

    return loadImage(file).then(image => {
        // 浏览器解不了的格式（比如 iPhone 默认的 HEIC）会走到这里。
        // 原样交出去，让后端去判断，而不是在这里静默失败
        if (!image) {
            return file
        }

        const scale = Math.min(1, maxEdge / Math.max(image.width, image.height))
        const canvas = document.createElement('canvas')
        canvas.width = Math.max(1, Math.round(image.width * scale))
        canvas.height = Math.max(1, Math.round(image.height * scale))

        const ctx = canvas.getContext('2d')
        // JPEG 没有透明通道。不铺白底的话，透明像素会被填成黑色
        ctx.fillStyle = '#fff'
        ctx.fillRect(0, 0, canvas.width, canvas.height)
        ctx.drawImage(image, 0, 0, canvas.width, canvas.height)

        // 先试着保住 PNG 的透明通道。PNG 是无损的，调质量参数没用，
        // 所以只能靠前面缩尺寸来减体积
        if (file.type === 'image/png') {
            return toBlob(canvas, 'image/png').then(png => {
                if (png && png.size <= maxBytes) {
                    return new File([png], file.name, {type: 'image/png'})
                }
                // 缩完还超标就退回 JPEG：透明会丢，但至少传得上去
                return encodeJpeg(canvas, file, maxBytes)
            })
        }

        return encodeJpeg(canvas, file, maxBytes)
    })
}

/** 逐步降质量，直到进入体积预算 */
async function encodeJpeg(canvas, source, maxBytes) {
    for (const quality of [0.85, 0.7, 0.55, 0.4]) {
        const blob = await toBlob(canvas, 'image/jpeg', quality)
        if (blob && blob.size <= maxBytes) {
            const name = source.name.replace(/\.[^.]+$/, '') + '.jpg'
            return new File([blob], name, {type: 'image/jpeg'})
        }
    }
    // 压到最低还超标：把原文件交出去，让后端给出明确提示。
    // 这里静默返回一个超标文件也比假装成功好
    return source
}

/**
 * 把图片读成 Image 对象，失败时 resolve(null)。
 *
 * onerror 这个回调是必须的：浏览器解不了的格式会走它。
 * 少了它，Promise 永远不会 resolve，调用方就一直卡在等 ——
 * 表现是上传界面一直转圈、而且不报任何错。
 */
function loadImage(file) {
    return new Promise(resolve => {
        const url = URL.createObjectURL(file)
        const image = new Image()
        const finish = result => {
            URL.revokeObjectURL(url)
            resolve(result)
        }
        image.onload = () => finish(image)
        image.onerror = () => finish(null)
        image.src = url
    })
}

function toBlob(canvas, type, quality) {
    return new Promise(resolve => canvas.toBlob(resolve, type, quality))
}

export function compressAvatar(file) {
    if (file.size <= 100 * 1024) {
        return Promise.resolve(file)
    }

    return new Promise(resolve => {
        const image = new Image()
        const url = URL.createObjectURL(file)

        image.onload = () => {
            const canvas = document.createElement('canvas')
            const maxSize = 512
            const scale = Math.min(
                1,
                maxSize / Math.max(image.width, image.height)
            )

            canvas.width = image.width * scale
            canvas.height = image.height * scale

            canvas.getContext('2d').drawImage(
                image, 0, 0, canvas.width, canvas.height
            )

            canvas.toBlob(blob => {
                resolve(new File([blob], 'avatar.jpg', {
                    type: 'image/jpeg'
                }))
                URL.revokeObjectURL(url)
            }, 'image/jpeg', 0.8)
        }

        // 原先没有这个回调：遇到浏览器解不了的图片格式，
        // 这个 Promise 永远不 resolve，头像上传会一直转圈且不报错
        image.onerror = () => {
            URL.revokeObjectURL(url)
            resolve(file)
        }

        image.src = url
    })
}
