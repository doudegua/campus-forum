import axios from 'axios';
import {ElMessage} from "element-plus";

const authItemName = "access_token";

const defaultFailure = (message, code, url) => {
    console.warn(`Request URL: ${url}, Status: ${code}, Message: ${message}`);
    ElMessage.warning(message);
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
function logout(success, failure = defaultFailure) {
    get('api/auth/logout', () => {
        deleteAccessToken();
        ElMessage.success('退出登录成功');
        success();
    }, failure)
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
