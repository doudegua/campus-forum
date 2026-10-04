import axios from 'axios';
import {ElMessage} from "element-plus";

const authItemName = "access_token";

const defaultFailure = (message, code, url) => {
    console.warn(`Request URL: ${url}, Status: ${code}, Message: ${message}`);
    // ⚠️ 兜底：message 可能是 undefined。
    //
    // 正常流程不会 —— 后端所有失败都返回 RestBean，里面一定有 message。
    // 但如果响应体不是那个信封（比如被代理/网关拦截返回了空 body、
    // 或者某个中间件改了响应），data.message 就是 undefined，
    // ElMessage.warning(undefined) 会在界面上弹一个空的/写着 undefined 的提示，
    // 比什么都不提示更让人困惑。
    // 这里给个能看懂的兜底文案，并保留 console.warn 方便排查真实原因。
    ElMessage.warning(message || '请求失败，请稍后重试');
}

const defaultError = (error) => {
    console.error(error);
    ElMessage.warning('发生了错误');
}

function storeAccessToken(token, remember, expire) {
    const authObj = {token: token, expire: expire};
    const str = JSON.stringify(authObj);
    if(remember){
        localStorage.setItem(authItemName, str);
    } else {
        sessionStorage.setItem(authItemName, str);
    }
}

function takeAccessToken() {
    const str = localStorage.getItem(authItemName) || sessionStorage.getItem(authItemName);
    if(!str) {
        return null;
    }
    let authObj;
    try {
        authObj = JSON.parse(str);
    } catch (e) {
        deleteAccessToken();
        return null;
    }
    // Date is serialized to an ISO string in storage; parse it before comparing.
    const expire = new Date(authObj.expire);
    if(!authObj.token || Number.isNaN(expire.getTime()) || expire <= new Date()) {
        deleteAccessToken();
        ElMessage.warning('登录状态过期，请重新登录');
        return null;
    }
    return authObj.token;
}

function deleteAccessToken() {
    localStorage.removeItem(authItemName);
    sessionStorage.removeItem(authItemName);
}

function accessHeader() {
    const token = takeAccessToken();
    return token? {
        'Authorization': `Bearer ${takeAccessToken()}`
    } : {}
}

function internalPost(url, requestData, header, success, failure, error = defaultError) {
    axios.post(url, requestData, {headers: header}).then(({data}) => {
        if(data.code === 200) {
            success(data.data);
        } else {
            failure(data.message, data.code, url);
        }
    }).catch(err => error(err));
}

function get(url, success, failure = defaultFailure) {
    internalGet(url, accessHeader(), success, failure);
}

function post(url, data, success, failure = defaultFailure) {
    internalPost(url, data, accessHeader(), success, failure);
}

function internalGet(url, header, success, failure, error = defaultError) {
    axios.get(url, {headers: header}).then(({data}) => {
        if(data.code === 200) {
            success(data.data);
        } else {
            failure(data.message, data.code, url);
        }
    }).catch(err => error(err));
}

function login(username, password, remember, success, failure = defaultFailure) {
    internalPost('api/auth/login', {
        username: username,
        password: password,
    }, {
        'content-type': 'application/x-www-form-urlencoded'
    }, (data) => {
        storeAccessToken(data.token, remember, data.expire);
        ElMessage.success(`登录成功，欢迎 ${username} 进入`);
        success(data);
    }, failure);
}

// function logout() {
//     internalGet('api/auth/logout', {}, () => {
//
//     })
// }
/**
 * 退出登录。
 *
 * ---------------------------------------------------------------------------
 * 关键：**本地登出必须无条件执行**，不能等接口成功
 * ---------------------------------------------------------------------------
 * 原来的写法是"接口成功 → 清本地 token → 跳转"，失败就什么都不做。
 * 结果：后端那次请求因为任何原因失败（token 已过期、网络抖动、被拦），
 * 用户点了"退出登录"却**还留在登录态** —— 页面看起来毫无反应。
 *
 * 但"退出登录"这个动作的主体是**本地**：用户要的是"我不想再以这个身份待着了"。
 * 服务端那一下只是顺手把 token 拉进黑名单（防它被人捡去继续用），
 * 属于**清理动作**，不是登出成功的前提。清理失败不该拦住用户登出。
 *
 * 所以顺序改成：先清本地、再尝试通知服务端。
 * 服务端失败时只记一条日志 —— 用户已经达到目的了，不该再弹红色报错烦他。
 *
 * 顺带解决一个体验问题：token 过期时 takeAccessToken() 会先自己清掉再返回 null，
 * 于是请求不带 Authorization，后端必然返回"退出登录失败"。
 * 按老写法用户就会看到这个报错而且登不出去 —— 明明他只是想退出。
 */
function logout(success, failure = defaultFailure) {
    // 先本地登出
    deleteAccessToken();

    // 再通知服务端把 token 拉黑。失败也不影响本地已经登出的事实。
    get('api/auth/logout', () => {
        ElMessage.success('退出登录成功');
        success();
    }, (message, code, url) => {
        // 不再走 failure（那会弹提示、也不跳转），只留日志
        console.warn(`服务端登出失败（本地已登出）：${url} code=${code} message=${message}`);
        ElMessage.success('已退出登录');
        success();
    })
}

function unauthorized() {
    return !takeAccessToken();
}

function getBlob(url, success, error = defaultError) {
    axios.get(url, {
        headers: accessHeader(),
        responseType: 'blob'
    }).then(response => {
        success(response.data)
    }).catch(error)
}

export {login, logout, get, post, unauthorized, accessHeader, getBlob};
