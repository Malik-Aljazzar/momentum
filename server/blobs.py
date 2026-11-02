"""Blob storage for user uploads (profile pictures). MinIO in dev/prod, memory in tests."""
import asyncio
import io
import logging
import os

log = logging.getLogger(__name__)


class BlobUnavailable(Exception):
    """The blob store cannot be reached."""


class StoreFull(Exception):
    """The blob store's disk is full (MinIO refuses writes below its free-space threshold)."""


MINIO_FULL_CODES = ("XMinioStorageFull", "StorageFull", "InsufficientStorage")


class MemoryStore:
    def __init__(self):
        self.objects = {}

    async def setup(self):
        pass

    async def put(self, key, data, content_type):
        self.objects[key] = (data, content_type)

    async def get(self, key):
        return self.objects.get(key)

    async def delete(self, key):
        self.objects.pop(key, None)

    async def sizes(self):
        return {k: len(v[0]) for k, v in self.objects.items()}


class MinioStore:
    """S3-compatible store via the official MinIO client (sync client run in a thread)."""

    def __init__(self, endpoint, access_key, secret_key, bucket, secure=False):
        from minio import Minio

        self.client = Minio(endpoint, access_key=access_key, secret_key=secret_key, secure=secure)
        self.bucket = bucket
        self.endpoint = endpoint

    @classmethod
    def from_env(cls):
        return cls(
            endpoint=os.environ.get("MINIO_ENDPOINT", "localhost:9000"),
            access_key=os.environ.get("MINIO_ACCESS_KEY", "momentum"),
            secret_key=os.environ.get("MINIO_SECRET_KEY", "momentum-dev-secret"),
            bucket=os.environ.get("MINIO_BUCKET", "momentum-avatars"),
            secure=os.environ.get("MINIO_SECURE", "0") == "1",
        )

    async def _run(self, fn, *args, **kwargs):
        from minio.error import S3Error

        try:
            return await asyncio.to_thread(fn, *args, **kwargs)
        except S3Error as e:
            if e.code in MINIO_FULL_CODES:
                raise StoreFull(e.message) from e
            raise
        except Exception as e:  # connection refused, timeouts, ...
            raise BlobUnavailable(str(e)) from e

    async def setup(self):
        try:
            if not await self._run(self.client.bucket_exists, self.bucket):
                await self._run(self.client.make_bucket, self.bucket)
        except BlobUnavailable as e:
            log.warning("MinIO at %s not reachable (%s) - profile pictures disabled until it is.",
                        self.endpoint, e)

    async def put(self, key, data, content_type):
        await self._run(self.client.put_object, self.bucket, key, io.BytesIO(data), len(data),
                        content_type=content_type)

    async def get(self, key):
        from minio.error import S3Error

        def read():
            res = self.client.get_object(self.bucket, key)
            try:
                return res.read(), res.headers.get("Content-Type", "application/octet-stream")
            finally:
                res.close()
                res.release_conn()

        try:
            return await self._run(read)
        except S3Error as e:
            if e.code == "NoSuchKey":
                return None
            raise

    async def delete(self, key):
        await self._run(self.client.remove_object, self.bucket, key)

    async def sizes(self):
        """{key: bytes} of every object (one listing of the bucket)."""
        def listing():
            return {o.object_name: o.size or 0 for o in self.client.list_objects(self.bucket, recursive=True)}
        try:
            return await self._run(listing)
        except BlobUnavailable:
            return {}
